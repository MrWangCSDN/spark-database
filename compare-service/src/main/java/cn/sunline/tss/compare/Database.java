package cn.sunline.tss.compare;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

/** 字段直接来自 TSS_BCOMP_DATABASE_CONFIG_NEW，保留原 AES/Base64 协议。 */
public record Database(String url, String user, String password, String driver, String encryption, String key) {
    public String plaintextPassword() throws Exception {
        if (!"1".equals(encryption)) return password;
        var cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(key), "AES"));
        return new String(cipher.doFinal(Base64.getDecoder().decode(password)));
    }
    @Override public String toString() { return "Database[connection redacted]"; }
}
