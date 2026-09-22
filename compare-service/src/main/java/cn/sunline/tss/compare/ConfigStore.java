package cn.sunline.tss.compare;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ConfigStore implements cn.sunline.tss.compare.configuration.CacheReloader {
    private final LegacyRepository repository;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());
    public ConfigStore(LegacyRepository repository) { this.repository = repository; }
    @Override public void reload() {
        ControlPlaneLock.call(() -> {
            snapshot = repository.loadConfiguration();
            return null;
        });
    }
    public Snapshot snapshot() { return snapshot; }
    public record Snapshot(Map<Long,String> names, Map<Long,Database> databases) {}
}
