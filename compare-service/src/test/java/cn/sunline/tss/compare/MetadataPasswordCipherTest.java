package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataPasswordCipherTest {
    @Test void encryptedValueUsesRandomSaltAndIvAndDecryptsWithTheRuntimeKey() {
        String first = MetadataPasswordCipher.encryptor("runtime-only-key").encrypt("database-password");
        String second = MetadataPasswordCipher.encryptor("runtime-only-key").encrypt("database-password");
        assertThat(first).isNotEqualTo(second);
        assertThat(MetadataPasswordCipher.encryptor("runtime-only-key").decrypt(first)).isEqualTo("database-password");
        assertThat(MetadataPasswordCipher.encryptor("runtime-only-key").decrypt(second)).isEqualTo("database-password");
    }
}
