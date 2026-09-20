package cn.sunline.tss.compare;

import org.apache.spark.launcher.SparkAppHandle;
import org.apache.spark.launcher.SparkLauncher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@Component
public class SparkJobLauncher implements JobLauncher {
    private static final Logger log=LoggerFactory.getLogger(SparkJobLauncher.class);
    private final Environment env;
    public SparkJobLauncher(Environment env) { this.env=env; }
    @Override public void submit(String[] args) throws Exception {
        if(args==null || args.length!=21) throw new IllegalArgumentException("Spark库表作业必须传入21个参数");
        String resource=env.getRequiredProperty("app_resource");
        if(!Files.isRegularFile(Path.of(resource))) throw new IllegalArgumentException("app_resource 指定的作业包不存在");
        Map<String,String> childEnv=new HashMap<>();
        String hadoop=env.getProperty("hadoop_conf_dir");
        if(hadoop!=null && !hadoop.isBlank()) childEnv.put("HADOOP_CONF_DIR",hadoop);
        var launcher=new SparkLauncher(childEnv)
            .setSparkHome(env.getRequiredProperty("spark_home"))
            .setMaster(env.getRequiredProperty("spark.master.path"))
            .setAppResource(resource)
            .setMainClass(tableMainClass(env.getProperty("spark_mainClass","cn.sunline.tss.tssveri.spark.SparkHandlerTable")))
            .setDeployMode(env.getProperty("spark.submit.deployMode","client"))
            .setVerbose(false).addAppArgs(args);
        // 使用原资源配置键；默认值来自原 application-spark.properties。
        Map<String,String> defaults=Map.ofEntries(
            Map.entry("spark.cores.max","144"),Map.entry("spark.executor.cores","4"),
            Map.entry("spark.executor.memory","10g"),Map.entry("spark.executor.memoryOverhead","2g"),
            Map.entry("spark.executor.instances","36"),Map.entry("spark.default.parallelism","500"),
            Map.entry("spark.driver.maxResultSize","6g"),Map.entry("spark.sql.shuffle.partitions","2000"),
            Map.entry("spark.driver.memory","6g"),Map.entry("spark.network.timeout","300"));
        defaults.forEach((key,value) -> launcher.setConf(key,env.getProperty(key,value)));
        String opens="--add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED -XX:+IgnoreUnrecognizedVMOptions";
        launcher.setConf("spark.driver.extraJavaOptions",env.getProperty("spark.driver.extraJavaOptions",opens));
        launcher.setConf("spark.executor.extraJavaOptions",env.getProperty("spark.executor.extraJavaOptions",opens));
        for(String jar:env.getProperty("compare.spark-jars","").split(",")) {
            if(!jar.isBlank()) launcher.addJar(jar.trim());
        }
        // 不启用verbose，避免把21参数中的连接密码打印成提交命令。
        if("true".equalsIgnoreCase(env.getProperty("spark.log.flag","false"))) launcher.redirectToLog("tss.spark.job");
        var finished=new CountDownLatch(1);
        SparkAppHandle handle=launcher.startApplication(new SparkAppHandle.Listener() {
            @Override public void stateChanged(SparkAppHandle app) {
                log.info("Spark application={}, state={}",app.getAppId(),app.getState());
                if(app.getState().isFinal()) finished.countDown();
            }
            @Override public void infoChanged(SparkAppHandle app) { }
        });
        try {
            long timeout=env.getProperty("compare.spark-timeout-seconds",Long.class,0L);
            if(timeout>0) {
                if(!finished.await(timeout,TimeUnit.SECONDS)) throw new TimeoutException("Spark作业超过配置时限");
            } else finished.await();
            if(handle.getState()!=SparkAppHandle.State.FINISHED) throw new IllegalStateException("Spark终态="+handle.getState());
        } catch(InterruptedException | TimeoutException e) {
            handle.kill(); throw e;
        } finally { handle.disconnect(); }
    }
    static String tableMainClass(String configured) {
        String[] classes=configured.split(",",-1);
        String selected=(classes.length==1?classes[0]:classes.length==2?classes[1]:"").trim();
        if(selected.isEmpty()) throw new IllegalArgumentException("spark_mainClass 需为库表类名或原文件类名,库表类名");
        return selected;
    }
}
