package cn.sunline.tss.compare;

@FunctionalInterface
public interface JobLauncher {
    /** 返回前必须等待作业结束；非正常终态抛出异常。 */
    void submit(String[] args) throws Exception;

    /** Stop running applications in one batch without affecting other batches. */
    default void cancelBatch(String batchId) { }
}
