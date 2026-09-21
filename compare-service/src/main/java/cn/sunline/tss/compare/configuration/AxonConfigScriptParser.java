package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiException;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AxonConfigScriptParser {
    private static final Pattern INSERT = Pattern.compile("(?is)^INSERT\\s+INTO\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*VALUES\\s*(.+)$");
    private static final List<String> CONF_COLUMNS = List.of("bcomp_index","bcomp_module","bcomp_name","bcomp_memo","bcomp_type","bcomp_range","bcomp_time_node","bcomp_state");
    private static final List<String> HASH_CONF_COLUMNS = List.of("bcomp_index","bcomp_module","bcomp_name","bcomp_memo","bcomp_type","bcomp_range","bcomp_time_node","bcomp_state","bcomp_partition_num","bcomp_shard_strategy");
    private static final List<String> FIELD_COLUMNS = List.of("bcomp_index","field_name","field_type","field_memo","field_old_index","field_new_index","field_index_flag","field_enum_flag");
    private static final List<String> SQL_COLUMNS = List.of("bcomp_index","orig_sql","orig_database_id","dest_sql","dest_database_id");

    ParseResult parse(Reader source, Consumer<Command> executor) {
        State state = new State();
        try (PushbackReader reader = new PushbackReader(source, 2)) {
            StringBuilder statement = new StringBuilder();
            boolean quoted = false;
            boolean lineComment = false;
            boolean blockComment = false;
            int value;
            while ((value = reader.read()) != -1) {
                char c = (char) value;
                if (lineComment) {
                    if (c == '\n' || c == '\r') lineComment = false;
                    continue;
                }
                if (blockComment) {
                    if (c == '*') {
                        int next = reader.read();
                        if (next == '/') blockComment = false;
                        else if (next != -1) reader.unread(next);
                    }
                    continue;
                }
                if (!quoted && c == '-') {
                    int next = reader.read();
                    if (next == '-') { lineComment = true; continue; }
                    if (next != -1) reader.unread(next);
                }
                if (!quoted && c == '/') {
                    int next = reader.read();
                    if (next == '*') { blockComment = true; continue; }
                    if (next != -1) reader.unread(next);
                }
                if (c == '\0') invalid();
                if (c == '\'' && quoted) {
                    statement.append(c);
                    int next = reader.read();
                    if (next == '\'') statement.append((char) next);
                    else {
                        quoted = false;
                        if (next != -1) reader.unread(next);
                    }
                    continue;
                }
                if (c == '\'' && !quoted) {
                    quoted = true;
                    statement.append(c);
                    continue;
                }
                if (c == ';' && !quoted) {
                    accept(statement.toString(), state, executor);
                    statement.setLength(0);
                } else {
                    statement.append(c);
                }
            }
            if (quoted || blockComment || !statement.toString().trim().isEmpty()) invalid();
        } catch (IOException exception) {
            throw ApiException.badRequest("CONFIG_SCRIPT_READ_FAILED", "配置脚本读取失败");
        }
        if (!state.committed) invalid();
        return new ParseResult(state.confRows, state.fieldRows, state.sqlRows, state.hasHashConfigurations);
    }

    private void accept(String raw, State state, Consumer<Command> executor) {
        String statement = stripBom(raw).trim();
        if (statement.isEmpty()) return;
        if (state.committed) invalid();
        if (state.step == 0 && statement.equalsIgnoreCase("START TRANSACTION")) { state.step++; return; }
        if (state.step == 1 && truncate(statement, "tss_bcomp_field")) { executor.accept(Command.clear("TSS_BCOMP_FIELD")); state.step++; return; }
        if (state.step == 2 && truncate(statement, "tss_bcomp_table_sql")) { executor.accept(Command.clear("TSS_BCOMP_TABLE_SQL")); state.step++; return; }
        if (state.step == 3 && truncate(statement, "tss_bcomp_conf")) { executor.accept(Command.clear("TSS_BCOMP_CONF")); state.step++; return; }
        if (state.step < 4) invalid();
        if (statement.equalsIgnoreCase("COMMIT")) {
            if (state.phase != 2 || state.confRows < 1 || state.fieldRows < 1 || state.sqlRows != state.confRows) invalid();
            state.committed = true;
            return;
        }
        Matcher matcher = INSERT.matcher(statement);
        if (!matcher.matches()) invalid();
        String table = matcher.group(1).toLowerCase(Locale.ROOT);
        List<String> columns = Pattern.compile("\\s*,\\s*").splitAsStream(matcher.group(2).trim()).map(value -> value.toLowerCase(Locale.ROOT)).toList();
        List<String> expected;
        int phase;
        boolean hashConfiguration = table.equals("tss_bcomp_conf") && columns.equals(HASH_CONF_COLUMNS);
        if (table.equals("tss_bcomp_conf")) { expected=hashConfiguration ? HASH_CONF_COLUMNS : CONF_COLUMNS; phase=0; }
        else if (table.equals("tss_bcomp_field")) { expected=FIELD_COLUMNS; phase=1; }
        else if (table.equals("tss_bcomp_table_sql")) { expected=SQL_COLUMNS; phase=2; }
        else { invalid(); return; }
        if (!columns.equals(expected) || phase < state.phase || phase > state.phase + 1) invalid();
        if (phase > state.phase && (state.phase == 0 ? state.confRows : state.fieldRows) == 0) invalid();
        state.phase = phase;
        int rows = countLiteralRows(matcher.group(3), expected.size(), hashConfiguration);
        state.hasHashConfigurations |= hashConfiguration;
        if (phase == 0) state.confRows += rows;
        else if (phase == 1) state.fieldRows += rows;
        else state.sqlRows += rows;
        executor.accept(Command.insert(statement));
    }

    private int countLiteralRows(String values, int columnCount, boolean hashConfiguration) {
        int position=0, rows=0;
        while (true) {
            position=skip(values,position);
            if (position>=values.length()) return rows;
            if (values.charAt(position++)!='(') invalid();
            int columns=0;
            while (true) {
                position=skip(values,position);
                if (position>=values.length()) invalid();
                int tokenStart = position;
                char c=values.charAt(position);
                if (c=='\'') position=stringLiteral(values,position);
                else {
                    int start=position;
                    if (c=='-') position++;
                    while(position<values.length() && Character.isDigit(values.charAt(position))) position++;
                    if(position==start || (c=='-' && position==start+1)) invalid();
                }
                if (hashConfiguration && columns == 8) validatePartitionCount(values.substring(tokenStart, position));
                if (hashConfiguration && columns == 9 && !"'HASH'".equalsIgnoreCase(values.substring(tokenStart, position))) invalid();
                columns++;
                position=skip(values,position);
                if(position>=values.length()) invalid();
                char delimiter=values.charAt(position++);
                if(delimiter==')') break;
                if(delimiter!=',') invalid();
            }
            if(columns!=columnCount) invalid();
            rows++;
            position=skip(values,position);
            if(position>=values.length()) return rows;
            if(values.charAt(position++)!=',') invalid();
        }
    }

    private void validatePartitionCount(String token) {
        try {
            int count = Integer.parseInt(token);
            if (count < 1 || count > 256) invalid();
        } catch (NumberFormatException exception) {
            invalid();
        }
    }

    private int stringLiteral(String value,int position) {
        position++;
        while(position<value.length()) {
            if(value.charAt(position++)=='\'') {
                if(position<value.length() && value.charAt(position)=='\'') { position++; continue; }
                return position;
            }
        }
        invalid(); return position;
    }

    private int skip(String value,int position){while(position<value.length()&&Character.isWhitespace(value.charAt(position)))position++;return position;}
    private boolean truncate(String statement,String table){return statement.matches("(?is)^TRUNCATE\\s+TABLE\\s+"+table+"$");}
    private String stripBom(String value){return value.startsWith("\uFEFF")?value.substring(1):value;}
    private static void invalid(){throw ApiException.badRequest("INVALID_CONFIG_SCRIPT","配置脚本不符合 Axon 固定格式");}

    record Command(Kind kind,String value) {
        static Command clear(String table){return new Command(Kind.CLEAR,table);}
        static Command insert(String sql){return new Command(Kind.INSERT,sql);}
    }
    enum Kind { CLEAR, INSERT }
    record ParseResult(long configurationCount,long fieldCount,long tableSqlCount,boolean hasHashConfigurations) {}
    private static final class State { int step; int phase; long confRows; long fieldRows; long sqlRows; boolean committed; boolean hasHashConfigurations; }
}
