package cn.sunline.tss.compare;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Component
public class TaskWorker implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private final LegacyRepository repository;
    private final ConfigStore configs;
    private final JobArguments arguments;
    private final JobLauncher launcher;
    private final Environment env;
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock(true);
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile boolean running;
    private volatile String status = "0";
    private ScheduledExecutorService executor;
    public TaskWorker(LegacyRepository repository, ConfigStore configs, JobArguments arguments, JobLauncher launcher, Environment env) {
        this.repository=repository; this.configs=configs; this.arguments=arguments; this.launcher=launcher; this.env=env;
    }
    @Override public synchronized void start() {
        if (running) return;
        status="1";
        try {
            configs.reload();
            int size=env.getProperty("tssveritask.executor.thread.maxPoolSize",Integer.class,1);
            long interval=env.getProperty("compare.poll-interval-ms",Long.class,2000L);
            if(size < 1 || interval < 1) throw new IllegalArgumentException("线程数和轮询间隔必须大于零");
            running=true; status="2";
            if (env.getProperty("tssveritask.executor.thread.enable",Boolean.class,true)) {
                executor=Executors.newScheduledThreadPool(size, r -> new Thread(r,"tss-table-worker"));
                for(int i=0;i<size;i++) executor.scheduleWithFixedDelay(this::runOne,0,interval,TimeUnit.MILLISECONDS);
            }
        } catch(RuntimeException e) { running=false; status="4"; throw e; }
    }
    void runOne() {
        if (!running || refreshing.get() || !gate.readLock().tryLock()) return;
        Task task=null;
        try {
            if (!running || refreshing.get()) return;
            task=repository.claim();
            if(task==null) return;
            var snapshot=configs.snapshot();
            String name=snapshot.names().get(task.index());
            if(name==null) throw new IllegalStateException("核对配置不在当前快照，请刷新");
            var sql=repository.sql(task);
            Database old=snapshot.databases().get(sql.oldId()), dest=snapshot.databases().get(sql.newId());
            if(old==null || dest==null) throw new IllegalStateException("数据库配置缺失，请刷新");
            log.info("提交库表任务 index={}, date={}, batch={}",task.index(),task.date(),task.batch());
            launcher.submit(arguments.build(task,name,old,dest,sql.oldSql(),sql.newSql()));
            if (!repository.hasCompletedResult(task)) repository.failIncompleteResult(task);
        } catch(Exception e) {
            // JDBC/Launcher异常可能含URL及作业参数，不把原异常消息写进日志或任务表。
            log.error("库表任务失败，task={}，异常类型={}",task,e.getClass().getSimpleName());
            if(task!=null) {
                try { repository.fail(task,"库表比对执行失败："+e.getClass().getSimpleName()+"，请检查Spark日志"); }
                catch(RuntimeException writeError) { log.error("任务失败状态回写失败，task={}，异常类型={}",task,writeError.getClass().getSimpleName()); }
            }
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { gate.readLock().unlock(); }
    }
    public RefreshResult refresh() {
        if (!refreshing.compareAndSet(false,true)) return new RefreshResult(500,"已有刷新任务正在执行");
        boolean locked=false;
        try {
            locked=gate.writeLock().tryLock(env.getProperty("compare.refresh-timeout-seconds",Long.class,300L),TimeUnit.SECONDS);
            if(!locked) return new RefreshResult(500,"等待任务超时");
            if(!running) return new RefreshResult(500,"服务未运行");
            configs.reload();
            return new RefreshResult(200,"刷新成功");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); return new RefreshResult(500,"刷新已中断");
        } catch(RuntimeException e) {
            return new RefreshResult(500,"刷新异常："+e.getClass().getSimpleName());
        } finally {
            if(locked) gate.writeLock().unlock(); refreshing.set(false);
        }
    }
    @Override public synchronized void stop() {
        running=false; status="3";
        if(executor!=null) {
            executor.shutdown();
            try {
                if(!executor.awaitTermination(env.getProperty("tssveritask.executor.thread.awaitTerminationSeconds",Long.class,60L),TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    executor.awaitTermination(10,TimeUnit.SECONDS);
                }
            } catch(InterruptedException e) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
        }
        status="0";
    }
    @Override public boolean isRunning() { return running; }
    public String status() { return status; }
    public record RefreshResult(int code,String msg) {}
}
