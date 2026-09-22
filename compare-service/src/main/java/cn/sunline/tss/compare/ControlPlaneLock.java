package cn.sunline.tss.compare;

import java.util.function.Supplier;

/** Serializes configuration changes and queue creation in this service instance. */
public final class ControlPlaneLock {
    private static final Object MONITOR = new Object();

    private ControlPlaneLock() {}

    public static <T> T call(Supplier<T> action) {
        synchronized (MONITOR) {
            return action.get();
        }
    }
}
