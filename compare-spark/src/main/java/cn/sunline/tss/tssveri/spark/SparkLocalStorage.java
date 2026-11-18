package cn.sunline.tss.tssveri.spark;

import lombok.extern.slf4j.Slf4j;
import org.apache.spark.SparkEnv;

import java.util.ArrayList;

@Slf4j
final class SparkLocalStorage {
    private static String lastApplicationId;
    private static String lastExecutorId;

    private SparkLocalStorage() {}

    static synchronized void logOnce() {
        try {
            SparkEnv environment=SparkEnv.get();
            if(environment==null) return;
            String applicationId=environment.conf().get("spark.app.id","unknown");
            String executorId=environment.executorId();
            if(applicationId.equals(lastApplicationId)&&executorId.equals(lastExecutorId)) return;
            lastApplicationId=applicationId;
            lastExecutorId=executorId;

            var directories=new ArrayList<String>();
            for(var directory:environment.blockManager().diskBlockManager().localDirs()) {
                directories.add(directory.getAbsolutePath()+" (usableBytes="+directory.getUsableSpace()
                    +", totalBytes="+directory.getTotalSpace()+")");
            }
            log.info("Spark 本地存储 applicationId={}, executorId={}, directories={}, spark.rdd.compress={}",
                applicationId,executorId,directories,environment.conf().getBoolean("spark.rdd.compress",false));
        } catch(RuntimeException error) {
            log.warn("Spark 本地存储诊断读取失败，异常类型={}",error.getClass().getSimpleName());
        }
    }
}
