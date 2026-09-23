package cn.sunline.tss.compare;

import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.jasypt.salt.RandomSaltGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Generates ciphertext compatible with the Jasypt properties in application-local.properties. */
final class MetadataPasswordCipher {
    private MetadataPasswordCipher() { }

    static String encryptFromStdin() throws IOException {
        String key = System.getenv("JASYPT_ENCRYPTOR_PASSWORD");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("缺少 JASYPT_ENCRYPTOR_PASSWORD");
        String plaintext = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        if (plaintext.isEmpty()) throw new IllegalArgumentException("数据库密码不能为空");
        return "ENC(" + encryptor(key).encrypt(plaintext) + ")";
    }

    static StandardPBEStringEncryptor encryptor(String key) {
        StandardPBEStringEncryptor encryptor = new StandardPBEStringEncryptor();
        encryptor.setPassword(key);
        encryptor.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        encryptor.setKeyObtentionIterations(1000);
        encryptor.setSaltGenerator(new RandomSaltGenerator());
        encryptor.setIvGenerator(new RandomIvGenerator());
        encryptor.setStringOutputType("base64");
        return encryptor;
    }
}
