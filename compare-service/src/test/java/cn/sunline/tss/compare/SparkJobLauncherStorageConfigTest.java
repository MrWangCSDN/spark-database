package cn.sunline.tss.compare;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class SparkJobLauncherStorageConfigTest {
    private static final String RUNNING_SUBMIT="""
        #!/bin/sh
        printf '%s\\n' "$$" > "${0%/bin/spark-submit}/child.pid"
        printf 'invoked\\n' > "${0%/bin/spark-submit}/invoked.txt"
        exec sleep 30
        """;
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

    @Test void rejectsInvalidTimeoutBeforeStartingChildProcess() throws Exception {
        var environment=fakeSparkEnvironment(RUNNING_SUBMIT)
            .withProperty("compare.spark-timeout-seconds","not-a-number");
        try {
            assertThatThrownBy(()->new SparkJobLauncher(environment).submit(arguments()))
                .isInstanceOf(ConversionFailedException.class);
            assertThat(fixtureWasInvoked()).isFalse();
        } finally { stopFixtureChild(); }
    }

    @ParameterizedTest
    @CsvSource({"BATCH_CANCELLED,0","WAIT_TIMEOUT,1","WAIT_INTERRUPTED,0"})
    void recordsTerminationReasonAndTaskWithoutLoggingArguments(String reason,String timeout) throws Exception {
        var environment=fakeSparkEnvironment(RUNNING_SUBMIT)
            .withProperty("compare.spark-timeout-seconds",timeout);
        var launcher=new SparkJobLauncher(environment);
        var logger=(Logger)LoggerFactory.getLogger(SparkJobLauncher.class);
        var events=new ListAppender<ILoggingEvent>();events.start();logger.addAppender(events);
        var failure=new AtomicReference<Throwable>();
        var task=new Thread(()->{
            try { launcher.submit(arguments()); }
            catch(Exception error) { failure.set(error); }
        });
        try {
            task.start();
            assertThat(fixtureWasInvoked()).isTrue();
            if("BATCH_CANCELLED".equals(reason)) launcher.cancelBatch("storage-configuration-test");
            if("WAIT_INTERRUPTED".equals(reason)) task.interrupt();
            task.join(8000);
            assertThat(task.isAlive()).isFalse();
            Class<? extends Throwable> expectedFailure=switch(reason) {
                case "WAIT_TIMEOUT" -> TimeoutException.class;
                case "WAIT_INTERRUPTED" -> InterruptedException.class;
                default -> IllegalStateException.class;
            };
            assertThat(failure.get()).isInstanceOf(expectedFailure);
            var messages=events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(messages).anySatisfy(message->assertThat(message).contains(
                "提交Spark作业 resource=","taskIndex=42","date=20261119",
                "batch=storage-configuration-test","timeNode=DAY","timeoutSeconds="+timeout));
            assertThat(messages).anySatisfy(message->assertThat(message).contains(
                "Spark application=","taskIndex=42","date=20261119",
                "batch=storage-configuration-test","timeNode=DAY","timeoutSeconds="+timeout));
            assertThat(messages).anySatisfy(message->assertThat(message).contains(
                "reason="+reason,"application=","batch=storage-configuration-test"));
            assertThat(messages).noneMatch(message->message.contains("synthetic-password")||message.contains("synthetic-sql"));
        } finally {
            logger.detachAppender(events);
            task.interrupt();
            stopFixtureChild();
            task.join(3000);
        }
    }

    private List<String> submittedStorageConfiguration(String localDirectories,String compression) throws Exception {
        var environment=fakeSparkEnvironment("""
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
        if(localDirectories!=null) environment.withProperty("spark.local.dir",localDirectories);
        if(compression!=null) environment.withProperty("spark.rdd.compress",compression);

        assertThatThrownBy(()->new SparkJobLauncher(environment).submit(arguments()))
            .isInstanceOf(IllegalStateException.class).hasMessage("Spark终态=FAILED");

        Path sparkHome=directory.resolve("spark home");
        assertThat(sparkHome.resolve("invoked.txt")).hasContent("invoked\n");
        String captured=Files.readString(sparkHome.resolve("storage-conf.txt"));
        assertThat(captured).doesNotContain("synthetic-password","synthetic-sql");
        return captured.lines().toList();
    }

    private MockEnvironment fakeSparkEnvironment(String scriptBody) throws IOException {
        Path sparkHome=Files.createDirectory(directory.resolve("spark home"));
        Path script=Files.createDirectory(sparkHome.resolve("bin")).resolve("spark-submit");
        Files.writeString(script,scriptBody);
        assertThat(script.toFile().setExecutable(true)).isTrue();
        return new MockEnvironment()
            .withProperty("app_resource",nameCapableJobJar().toString())
            .withProperty("spark_home",sparkHome.toString())
            .withProperty("spark.master.path","local")
            .withProperty("compare.spark-timeout-seconds","5");
    }

    private String[] arguments() {
        String[] arguments=new String[21];Arrays.fill(arguments,"");
        arguments[3]="synthetic-password-never-capture";
        arguments[6]="42";arguments[7]="20261119";
        arguments[8]="storage-configuration-test";arguments[9]="DAY";
        arguments[14]="synthetic-sql-never-log";
        return arguments;
    }

    private boolean fixtureWasInvoked() throws InterruptedException {
        Path marker=directory.resolve("spark home/invoked.txt");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<deadline) {
            if(Files.exists(marker)) return true;
            Thread.sleep(10);
        }
        return false;
    }

    private void stopFixtureChild() throws Exception {
        Path pidFile=directory.resolve("spark home/child.pid");
        if(Files.exists(pidFile)) {
            var child=ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim()));
            if(child.isPresent()) {
                child.get().destroyForcibly();
                child.get().onExit().get(3,TimeUnit.SECONDS);
            }
        }
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
