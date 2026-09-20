package cn.sunline.tss.compare;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 与原 TssveritaskThread.getBcompTablePara 完全相同的位置协议。 */
@Component
public class JobArguments {
    private final Environment env;
    public JobArguments(Environment env) { this.env = env; }
    public String[] build(Task task, String name, Database old, Database dest, String oldSql, String newSql) throws Exception {
        String[] args = {name + "_" + task.index(), required("spark.datasource.url"),
            required("spark.datasource.username"), required("spark.datasource.password"),
            required("spark.datasource.driver-class-name"), env.getProperty("spark.datasource.batchExecute", "1000"),
            Long.toString(task.index()), task.date(), task.batch(), task.timeNode(),
            old.url(), old.user(), old.plaintextPassword(), old.driver(), oldSql,
            dest.url(), dest.user(), dest.plaintextPassword(), dest.driver(), newSql,
            task.verifyDate() == null ? "" : task.verifyDate()};
        for (int i=0; i<args.length; i++) {
            if (args[i] == null) throw new IllegalArgumentException("原作业参数缺失，位置=" + i);
        }
        return args;
    }
    private String required(String key) {
        String value = env.getProperty(key);
        if (value == null) throw new IllegalArgumentException("缺少配置项：" + key);
        return value;
    }
}
