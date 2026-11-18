package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class SparkJobLauncherStorageConfigTest {
    @TempDir Path directory;

    @Test void forwardsConfiguredLocalDirectoriesWithoutChangingSpacesOrComma() throws Exception {
        String localDirectories="/data/spark scratch,/mnt/spark-local";

        assertThat(submittedStorageConfiguration(localDirectories,null)).containsExactlyInAnyOrder(
            "spark.local.dir="+localDirectories,"spark.rdd.compress=true");
    }

    @Test void leavesLocalDirectoryUnsetWhenApplicationPropertyIsMissing() throws Exception {
        assertThat(submittedStorageConfiguration(null,null)).containsExactly("spark.rdd.compress=true");
    }

    @Test void leavesLocalDirectoryUnsetWhenApplicationPropertyIsBlank() throws Exception {
        assertThat(submittedStorageConfiguration("   ",null)).containsExactly("spark.rdd.compress=true");
    }

    @Test void keepsExplicitRddCompressionOverride() throws Exception {
        assertThat(submittedStorageConfiguration(null,"false")).containsExactly("spark.rdd.compress=false");
    }

    private List<String> submittedStorageConfiguration(String localDirectories,String compression) throws Exception {
        Path sparkHome=Files.createDirectory(directory.resolve("spark home"));
        Path script=Files.createDirectory(sparkHome.resolve("bin")).resolve("spark-submit");
        Files.writeString(script,"""
            #!/bin/sh
            capture_file="${0%/bin/spark-submit}/storage-conf.txt"
            printf 'invoked\\n' > "${0%/bin/spark-submit}/invoked.txt"
            : > "$capture_file"
            while [ "$#" -gt 0 ]; do
              if [ "$1" = "--conf" ]; then
                shift
                case "$1" in
                  spark.local.dir=*|spark.rdd.compress=*) printf '%s\\n' "$1" >> "$capture_file" ;;
                esac
              fi
              shift
            done
            exit 13
            """);
        assertThat(script.toFile().setExecutable(true)).isTrue();
        var environment=new MockEnvironment()
            .withProperty("app_resource",nameCapableJobJar().toString())
            .withProperty("spark_home",sparkHome.toString())
            .withProperty("spark.master.path","local")
            .withProperty("compare.spark-timeout-seconds","5");
        if(localDirectories!=null) environment.withProperty("spark.local.dir",localDirectories);
        if(compression!=null) environment.withProperty("spark.rdd.compress",compression);
        String[] arguments=new String[21];
        Arrays.fill(arguments,"");
        arguments[0]="synthetic-password-never-capture";
        arguments[8]="storage-configuration-test";

        assertThatThrownBy(()->new SparkJobLauncher(environment).submit(arguments))
            .isInstanceOf(IllegalStateException.class).hasMessage("Spark终态=FAILED");

        assertThat(sparkHome.resolve("invoked.txt")).hasContent("invoked\n");
        String captured=Files.readString(sparkHome.resolve("storage-conf.txt"));
        assertThat(captured).doesNotContain(arguments[0]);
        return captured.lines().toList();
    }

    private Path nameCapableJobJar() throws IOException {
        Path file=directory.resolve("job.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(file))) {
            for(String name:List.of("SparkHandlerTable","QueryFieldResolver")) {
                output.putNextEntry(new JarEntry("cn/sunline/tss/tssveri/spark/"+name+".class"));
                output.write(new byte[]{1,2,3});
                output.closeEntry();
            }
        }
        return file.toRealPath();
    }
}
