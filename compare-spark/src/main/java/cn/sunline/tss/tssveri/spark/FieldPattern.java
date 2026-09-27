package cn.sunline.tss.tssveri.spark;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** An ordered, unambiguous field-set identity. */
final class FieldPattern implements Serializable {
    private static final ObjectMapper JSON = new ObjectMapper();
    final String json;
    final String id;
    FieldPattern(List<String> names) {
        if (names == null || names.isEmpty()) throw new IllegalArgumentException("差异字段组合不能为空");
        try {
            json = JSON.writeValueAsString(names);
            id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("差异字段组合无法编码", e);
        }
    }
    FieldPattern(String json,String id) {
        if(json==null||id==null) throw new IllegalArgumentException("差异字段组合标识不能为空");
        this.json=json;this.id=id;
    }
}
