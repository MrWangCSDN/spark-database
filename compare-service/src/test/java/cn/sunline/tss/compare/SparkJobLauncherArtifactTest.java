package cn.sunline.tss.compare;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SparkJobLauncherArtifactTest {
    private static final String MAIN_CLASS="cn.sunline.tss.tssveri.spark.SparkHandlerTable";
    private static final String RESOLVER_CLASS="cn.sunline.tss.tssveri.spark.QueryFieldResolver";

    @TempDir Path directory;

    @Test void defaultNameModeRejectsLegacyAppResourceBeforeReadingSparkHome() throws Exception {
        Path legacy=jar("legacy-job.jar",MAIN_CLASS);
        var launcher=new SparkJobLauncher(new MockEnvironment().withProperty("app_resource",legacy.toString()));

        assertThatThrownBy(()->launcher.submit(arguments()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app_resource")
            .hasMessageContaining(legacy.toString())
            .hasMessageContaining("QueryFieldResolver");
    }

    @Test void rejectsAppResourceMissingTheSelectedMainClass() throws Exception {
        Path modern=jar("modern-job.jar",MAIN_CLASS,RESOLVER_CLASS);
        String selectedMain="cn.example.MissingTableHandler";
        var environment=new MockEnvironment().withProperty("app_resource",modern.toString())
            .withProperty("spark_mainClass","cn.example.FileHandler,"+selectedMain);

        assertThatThrownBy(()->new SparkJobLauncher(environment).submit(arguments()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("app_resource")
            .hasMessageContaining(modern.toString())
            .hasMessageContaining(selectedMain);
    }

    @Test void positionModeAllowsLegacyAppResourceAndReachesSparkHomeValidation() throws Exception {
        Path legacy=jar("legacy-job.jar",MAIN_CLASS);
        var environment=new MockEnvironment().withProperty("app_resource",legacy.toString())
            .withProperty("compare.field-match-by-name","false");

        assertThatThrownBy(()->new SparkJobLauncher(environment).submit(arguments()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spark_home");
    }

    @Test void nameCapableAppResourceReachesSparkHomeValidation() throws Exception {
        Path modern=jar("modern-job.jar",MAIN_CLASS,RESOLVER_CLASS);
        var environment=new MockEnvironment().withProperty("app_resource",modern.toString());

        assertThatThrownBy(()->new SparkJobLauncher(environment).submit(arguments()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spark_home");
    }

    private Path jar(String filename,String... classes) throws IOException {
        Path file=directory.resolve(filename);
        try(var output=new JarOutputStream(Files.newOutputStream(file))) {
            for(String className:classes) {
                output.putNextEntry(new JarEntry(className.replace('.','/')+".class"));
                output.write(new byte[]{1,2,3});
                output.closeEntry();
            }
        }
        return file.toRealPath();
    }

    private String[] arguments() {
        String[] args=new String[21];
        Arrays.fill(args,"");
        args[8]="artifact-test-batch";
        return args;
    }
}
