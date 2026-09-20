package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class JobArgumentsTest {
    @Test void keepsAll21LegacyPositionsAndEncryptedConnectionPassword() throws Exception {
        var env = new MockEnvironment().withProperty("spark.datasource.url", "jdbc:meta")
            .withProperty("spark.datasource.username", "meta-user").withProperty("spark.datasource.password", "meta-pass")
            .withProperty("spark.datasource.driver-class-name", "meta.Driver").withProperty("spark.datasource.batchExecute", "123");
        byte[] key = "0123456789abcdef".getBytes();
        var cipher = Cipher.getInstance("AES"); cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        var old = new Database("jdbc:old", "old-user", Base64.getEncoder().encodeToString(cipher.doFinal("old-pass".getBytes())), "old.Driver", "1", Base64.getEncoder().encodeToString(key));
        var dest = new Database("jdbc:new", "new-user", "new-pass", "new.Driver", "0", null);
        String sql = "(select * from T where d='${HDRQ}') t";
        var args = new JobArguments(env).build(new Task(22,"20260920","7","PM","20260919"), "客户", old, dest, sql, "T_NEW");
        assertThat(args).containsExactly("客户_22", "jdbc:meta", "meta-user", "meta-pass", "meta.Driver", "123", "22", "20260920", "7", "PM", "jdbc:old", "old-user", "old-pass", "old.Driver", sql, "jdbc:new", "new-user", "new-pass", "new.Driver", "T_NEW", "20260919");
        assertThat(old.toString()).doesNotContain("old-user", old.password(), old.key());
    }
    @Test void preservesEmptyBusinessDateAndEmptyPassword() throws Exception {
        var env = new MockEnvironment().withProperty("spark.datasource.url", "jdbc:meta").withProperty("spark.datasource.username", "u")
            .withProperty("spark.datasource.password", "").withProperty("spark.datasource.driver-class-name", "D");
        var db = new Database("jdbc:test", "u", "", "D", "0", null);
        var args = new JobArguments(env).build(new Task(1,"20260920","1","A",null), "x", db, db, "T", "T");
        assertThat(args[20]).isEmpty(); assertThat(args[3]).isEmpty(); assertThat(args[5]).isEqualTo("1000");
    }
}
