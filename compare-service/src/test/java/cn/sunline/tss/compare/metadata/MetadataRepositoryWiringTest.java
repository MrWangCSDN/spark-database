package cn.sunline.tss.compare.metadata;

import cn.sunline.tss.compare.database.DatabaseManagementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

class MetadataRepositoryWiringTest {
    @Test
    void databaseRepositoryInjectsTheConfiguredDialect() {
        Constructor<?>[] constructors = DatabaseManagementRepository.class.getConstructors();
        Constructor<?> selected = assertDoesNotThrow(() -> {
            for (Constructor<?> constructor : constructors) {
                if (constructor.isAnnotationPresent(Autowired.class)) return constructor;
            }
            throw new AssertionError("no @Autowired constructor");
        });
        assertEquals(3, selected.getParameterCount());
        assertEquals(MetadataSqlDialect.class, selected.getParameterTypes()[2]);
    }
}
