package cn.sunline.tss.compare.configuration;

import cn.sunline.tss.compare.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Permanent bindings survive configuration replacement; unnamed historical indexes stay reserved. */
final class StableConfigurationIds {
    private final JdbcTemplate jdbc;
    private final Map<String,Long> codes=new HashMap<>();
    private final Set<Long> reserved=new HashSet<>();
    private long next;

    StableConfigurationIds(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    void bootstrap() {
        // All callers own a transaction. The singleton row serializes allocators across service processes.
        next=jdbc.queryForObject("SELECT NEXT_INDEX FROM TSS_BCOMP_CONFIG_ALLOC_NEW WHERE ALLOCATION_ID=1 FOR UPDATE",Long.class);
        var indexCodes=new TreeMap<Long,Set<String>>();
        var originalCodes=new HashMap<String,String>();
        RowCallbackHandler names=rs->{
            long index=rs.getLong(1);String raw=rs.getString(2);
            if(index<=0 || raw==null || raw.isBlank()) return;
            String code=normalize(raw);
            indexCodes.computeIfAbsent(index,key->new TreeSet<>()).add(code);
            originalCodes.putIfAbsent(code,raw.trim());
        };
        jdbc.query("SELECT BCOMP_INDEX,CONFIGURATION_CODE FROM TSS_BCOMP_CONFIG_ID_NEW WHERE CONFIGURATION_CODE IS NOT NULL",names);
        jdbc.query("SELECT BCOMP_INDEX,BCOMP_NAME FROM TSS_BCOMP_CONF_NEW WHERE BCOMP_TYPE='2'",names);
        jdbc.query("SELECT BCOMP_INDEX,CONFIGURATION_NAME FROM TSS_BCOMP_RESULT_NEW",names);
        jdbc.query("SELECT BCOMP_INDEX,CONFIGURATION_NAME FROM TSS_BCOMP_BATCH_ITEM_NEW",names);
        for(var entry:indexCodes.entrySet()) {
            if(entry.getValue().size()!=1) ambiguous();
            String code=entry.getValue().iterator().next();
            Long previous=codes.putIfAbsent(code,entry.getKey());
            if(previous!=null && !previous.equals(entry.getKey())) ambiguous();
        }
        for(String table:List.of("TSS_BCOMP_CONFIG_ID_NEW","TSS_BCOMP_CONF_NEW","TSS_BCOMP_TABLE_SQL_NEW",
            "TSS_BCOMP_FIELD_NEW","TSS_BCOMP_TASK_NEW","TSS_BCOMP_DTL_NEW","TSS_BCOMP_RESULT_NEW",
            "TSS_BCOMP_FIELD_COUNT_NEW","TSS_BCOMP_FIELD_DIFF_NEW","TSS_BCOMP_BATCH_ITEM_NEW","TSS_BCOMP_FULL_RUN_NEW")) {
            jdbc.query("SELECT DISTINCT BCOMP_INDEX FROM "+table+" WHERE BCOMP_INDEX>0",(RowCallbackHandler)rs->reserved.add(rs.getLong(1)));
        }
        var stored=new HashSet<>(jdbc.query("SELECT BCOMP_INDEX FROM TSS_BCOMP_CONFIG_ID_NEW",(rs,n)->rs.getLong(1)));
        for(long index:new TreeSet<>(reserved)) if(!stored.contains(index))
            jdbc.update("INSERT INTO TSS_BCOMP_CONFIG_ID_NEW(BCOMP_INDEX) VALUES(?)",index);
        for(var entry:codes.entrySet()) jdbc.update("UPDATE TSS_BCOMP_CONFIG_ID_NEW SET CONFIGURATION_KEY=?,CONFIGURATION_CODE=? WHERE BCOMP_INDEX=?",
            key(entry.getKey()),originalCodes.get(entry.getKey()),entry.getValue());
        if(!reserved.isEmpty()) next=Math.max(next,increment(Collections.max(reserved)));
        saveNext();
    }

    long resolve(long proposed,String rawCode) {
        String code=normalize(rawCode);
        Long existing=codes.get(code);
        if(existing!=null) return existing;
        long index=reserved.contains(proposed)?next:proposed;
        jdbc.update("INSERT INTO TSS_BCOMP_CONFIG_ID_NEW(BCOMP_INDEX,CONFIGURATION_KEY,CONFIGURATION_CODE) VALUES(?,?,?)",index,key(code),rawCode.trim());
        reserved.add(index);codes.put(code,index);next=Math.max(next,increment(index));saveNext();
        return index;
    }

    void register(long index,String code) {
        Long existing=codes.get(normalize(code));
        if((existing!=null && existing!=index) || (existing==null && reserved.contains(index)))
            throw ApiException.conflict("CONFIGURATION_ID_CONFLICT","核对编号或固定标识已被当前或历史配置保留");
        if(resolve(index,code)!=index) throw ApiException.conflict("CONFIGURATION_ID_CONFLICT","核对编号与固定标识映射不一致");
    }

    static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String key(String code) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private long increment(long value) {
        try { return Math.addExact(value,1); }
        catch(ArithmeticException exhausted) { throw ApiException.conflict("CONFIGURATION_ID_EXHAUSTED","配置编号已达到支持上限"); }
    }
    private void saveNext() { jdbc.update("UPDATE TSS_BCOMP_CONFIG_ALLOC_NEW SET NEXT_INDEX=? WHERE ALLOCATION_ID=1",next); }
    private static void ambiguous() { throw ApiException.conflict("CONFIGURATION_IDENTITY_AMBIGUOUS","现有配置编号与历史固定标识存在歧义，请核对历史映射后重试"); }
}
