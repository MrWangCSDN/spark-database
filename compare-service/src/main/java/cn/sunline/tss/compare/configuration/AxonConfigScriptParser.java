package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiException;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.function.BiFunction;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AxonConfigScriptParser {
    private static final Pattern INSERT = Pattern.compile("(?is)^INSERT\\s+INTO\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)\\s*VALUES\\s*(.+)$");
    private static final Pattern UTF8_HEX_VALUE = Pattern.compile("(?i)CONVERT\\s*\\(\\s*0x([0-9a-f]+)\\s+USING\\s+utf8mb4\\s*\\)");
    private static final List<String> CONF_COLUMNS = List.of("bcomp_index","bcomp_module","bcomp_name","bcomp_memo","bcomp_type","bcomp_range","bcomp_time_node","bcomp_state");
    private static final List<String> HASH_CONF_COLUMNS = List.of("bcomp_index","bcomp_module","bcomp_name","bcomp_memo","bcomp_type","bcomp_range","bcomp_time_node","bcomp_state","bcomp_partition_num","bcomp_shard_strategy");
    private static final List<String> SAMPLE_CONF_COLUMNS = java.util.stream.Stream.concat(HASH_CONF_COLUMNS.stream(), java.util.stream.Stream.of("bcomp_sample_limit")).toList();
    private static final List<String> REVISER_CONF_COLUMNS = java.util.stream.Stream.concat(SAMPLE_CONF_COLUMNS.stream(), java.util.stream.Stream.of("reviser_name","reviser_username")).toList();
    private static final List<String> MAX_PARTITION_CONF_COLUMNS = java.util.stream.Stream.concat(REVISER_CONF_COLUMNS.stream(), java.util.stream.Stream.of("bcomp_max_partition_num")).toList();
    private static final List<String> IDENTITY_CONF_COLUMNS = java.util.stream.Stream.concat(MAX_PARTITION_CONF_COLUMNS.stream(), java.util.stream.Stream.of("source_table_name","configuration_name","configuration_scope")).toList();
    private static final List<List<String>> CONF_LAYOUTS = List.of(CONF_COLUMNS,HASH_CONF_COLUMNS,SAMPLE_CONF_COLUMNS,REVISER_CONF_COLUMNS,MAX_PARTITION_CONF_COLUMNS,IDENTITY_CONF_COLUMNS);
    private static final List<String> FIELD_COLUMNS = List.of("bcomp_index","field_name","field_type","field_memo","field_old_index","field_new_index","field_index_flag","field_enum_flag");
    private static final List<String> SQL_COLUMNS = List.of("bcomp_index","orig_sql","orig_database_id","dest_sql","dest_database_id");
    private static final Set<String> TEXT_COLUMNS = Set.of("bcomp_module","bcomp_name","bcomp_memo","bcomp_type",
        "bcomp_range","bcomp_time_node","bcomp_state","bcomp_shard_strategy","reviser_name","reviser_username",
        "field_name","field_type","field_memo","field_index_flag","field_enum_flag","orig_sql","dest_sql",
        "source_table_name","configuration_name","configuration_scope");

    ParseResult parse(Reader source, Consumer<Command> executor) {
        return parse(source,executor,(index,code)->index);
    }

    ParseResult parse(Reader source, Consumer<Command> executor, BiFunction<Long,String,Long> resolver) {
        State state = new State(resolver);
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
        return new ParseResult(state.confRows, state.fieldRows, state.sqlRows, state.hasHashConfigurations,
            Set.copyOf(state.explicitMaxPartitionIndexes));
    }

    private void accept(String raw, State state, Consumer<Command> executor) {
        String statement = stripBom(raw).trim();
        if (statement.isEmpty()) return;
        if (state.committed) invalid();
        if (state.step == 0 && statement.equalsIgnoreCase("START TRANSACTION")) { state.step++; return; }
        if (state.step == 1 && truncate(statement, "tss_bcomp_field_new")) { executor.accept(Command.clear("TSS_BCOMP_FIELD_NEW")); state.step++; return; }
        if (state.step == 2 && truncate(statement, "tss_bcomp_table_sql_new")) { executor.accept(Command.clear("TSS_BCOMP_TABLE_SQL_NEW")); state.step++; return; }
        if (state.step == 3 && truncate(statement, "tss_bcomp_conf_new")) { executor.accept(Command.clear("TSS_BCOMP_CONF_NEW")); state.step++; return; }
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
        if (table.equals("tss_bcomp_conf_new")) {
            if (!CONF_LAYOUTS.contains(columns)) invalid();
            expected=columns; phase=0;
        }
        else if (table.equals("tss_bcomp_field_new")) { expected=FIELD_COLUMNS; phase=1; }
        else if (table.equals("tss_bcomp_table_sql_new")) { expected=SQL_COLUMNS; phase=2; }
        else { invalid(); return; }
        if (!columns.equals(expected) || phase < state.phase || phase > state.phase + 1) invalid();
        if (phase > state.phase && (state.phase == 0 ? state.confRows : state.fieldRows) == 0) invalid();
        state.phase = phase;
        boolean hashConfiguration = phase == 0 && columns.size() >= HASH_CONF_COLUMNS.size();
        boolean maxPartitionConfiguration = phase == 0 && columns.size() >= MAX_PARTITION_CONF_COLUMNS.size();
        boolean reviserConfiguration = phase == 0 && (columns.equals(REVISER_CONF_COLUMNS) || maxPartitionConfiguration);
        ParsedValues values = parseLiteralRows(matcher.group(3), expected, hashConfiguration, reviserConfiguration,
            maxPartitionConfiguration,state,phase);
        int rows = values.rows();
        state.hasHashConfigurations |= hashConfiguration;
        state.explicitMaxPartitionIndexes.addAll(values.explicitMaxPartitionIndexes());
        if (phase == 0) state.confRows += rows;
        else if (phase == 1) state.fieldRows += rows;
        else state.sqlRows += rows;
        executor.accept(Command.insert(statement.substring(0, matcher.start(3)) + values.sql(), values.parameters()));
    }

    private ParsedValues parseLiteralRows(String values, List<String> columnNames, boolean hashConfiguration,
                                          boolean reviserConfiguration, boolean maxPartitionConfiguration,
                                          State state,int phase) {
        int columnCount=columnNames.size(),position=0,rows=0;
        StringBuilder sql=new StringBuilder(values.length());
        List<Object> parameters=new ArrayList<>();
        Set<Long> explicitMaxPartitionIndexes=new LinkedHashSet<>();
        while(true) {
            position=skip(values,position);
            if(position>=values.length()) break;
            if(values.charAt(position++)!='(') invalid();
            if(rows>0) sql.append(',');
            sql.append('(');
            int columns=0,indexParameter=parameters.size();
            long externalIndex=0;
            var texts=new ArrayList<String>();
            while(true) {
                position=skip(values,position);
                if(position>=values.length()) invalid();
                int tokenStart=position;
                char c=values.charAt(position);
                boolean textColumn=columns<columnCount && TEXT_COLUMNS.contains(columnNames.get(columns));
                String textValue=null;
                if(c=='\'') {
                    position=stringLiteral(values,position);
                    if(textColumn) textValue=values.substring(tokenStart+1,position-1).replace("''","'");
                } else if(textColumn && (c=='C' || c=='c')) {
                    Matcher encoded=UTF8_HEX_VALUE.matcher(values).region(position,values.length());
                    if(!encoded.lookingAt()) invalid();
                    textValue=decodeUtf8Hex(encoded.group(1));position=encoded.end();
                } else if(reviserConfiguration && columns>=SAMPLE_CONF_COLUMNS.size() && columns<REVISER_CONF_COLUMNS.size()) {
                    if(!values.regionMatches(true,position,"NULL",0,4)) invalid();
                    position+=4;
                } else {
                    int begin=position;
                    if(c=='-') position++;
                    while(position<values.length() && Character.isDigit(values.charAt(position))) position++;
                    if(position==begin || (c=='-' && position==begin+1)) invalid();
                }
                String token=values.substring(tokenStart,position);
                texts.add(textValue);
                if(columns>0) sql.append(',');
                if(columns==0) {
                    externalIndex=configurationIndex(token);
                    if(externalIndex<=0) invalid();
                    sql.append('?');parameters.add(null);
                } else if(textValue!=null) {
                    sql.append('?');parameters.add(textValue);
                } else sql.append(token);
                if(hashConfiguration && columns==8) validatePartitionCount(token);
                if(hashConfiguration && columns==9 && !"HASH".equalsIgnoreCase(textValue)) invalid();
                if(hashConfiguration && columns==10) validateSampleLimit(token);
                if(maxPartitionConfiguration && columns==13) validatePartitionCount(token);
                columns++;
                position=skip(values,position);
                if(position>=values.length()) invalid();
                char delimiter=values.charAt(position++);
                if(delimiter==')') break;
                if(delimiter!=',') invalid();
            }
            if(columns!=columnCount) invalid();
            long internalIndex;
            if(phase==0) {
                String code=texts.get(2);
                if(code==null || code.isBlank() || code.trim().length()>100
                    || !state.codes.add(code.trim().toLowerCase(Locale.ROOT)) || state.indexes.containsKey(externalIndex)) invalid();
                if(columnNames.equals(IDENTITY_CONF_COLUMNS)) {
                    if(texts.get(14)==null || texts.get(14).isBlank() || texts.get(14).length()>256
                        || texts.get(15)==null || texts.get(15).isBlank() || texts.get(15).length()>200 || texts.get(16)==null) invalid();
                }
                internalIndex=state.resolver.apply(externalIndex,code);
                state.indexes.put(externalIndex,internalIndex);
            } else {
                Long mapped=state.indexes.get(externalIndex);
                if(mapped==null) { invalid();return null; }
                internalIndex=mapped;
            }
            parameters.set(indexParameter,internalIndex);
            if(maxPartitionConfiguration) explicitMaxPartitionIndexes.add(internalIndex);
            sql.append(')');rows++;
            position=skip(values,position);
            if(position>=values.length()) break;
            if(values.charAt(position++)!=',') invalid();
        }
        return new ParsedValues(rows,sql.toString(),List.copyOf(parameters),Set.copyOf(explicitMaxPartitionIndexes));
    }

    private long configurationIndex(String token) {
        if(token.startsWith("'")) token=token.substring(1,token.length()-1).replace("''","'");
        try { return Long.parseLong(token.trim()); }
        catch(NumberFormatException exception) { invalid(); return 0L; }
    }

    private String decodeUtf8Hex(String hex) {
        try {
            String value=StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(HexFormat.of().parseHex(hex))).toString();
            if(value.indexOf('\0')>=0) invalid();
            return value;
        } catch(IllegalArgumentException | CharacterCodingException exception) {
            invalid();
            return "";
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

    private void validateSampleLimit(String token) {
        try {
            int limit = Integer.parseInt(token);
            if (limit < -1 || limit > 10000) invalid();
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

    private record ParsedValues(int rows,String sql,List<Object> parameters,Set<Long> explicitMaxPartitionIndexes) {}
    record Command(Kind kind,String value,List<Object> parameters) {
        static Command clear(String table){return new Command(Kind.CLEAR,table,List.of());}
        static Command insert(String sql,List<Object> parameters){return new Command(Kind.INSERT,sql,parameters);}
    }
    enum Kind { CLEAR, INSERT }
    record ParseResult(long configurationCount,long fieldCount,long tableSqlCount,boolean hasHashConfigurations,
                       Set<Long> explicitMaxPartitionIndexes) {}
    private static final class State {
        int step; int phase; long confRows; long fieldRows; long sqlRows; boolean committed; boolean hasHashConfigurations;
        final Set<Long> explicitMaxPartitionIndexes=new LinkedHashSet<>();
        final Map<Long,Long> indexes=new HashMap<>();
        final Set<String> codes=new HashSet<>();
        final BiFunction<Long,String,Long> resolver;
        State(BiFunction<Long,String,Long> resolver) { this.resolver=resolver; }
    }
}
