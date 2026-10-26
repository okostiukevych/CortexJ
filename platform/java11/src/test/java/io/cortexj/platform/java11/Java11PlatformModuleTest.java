package io.cortexj.platform.java11;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java11PlatformModuleTest {

    @Test
    void moduleIdentityAndExecutorLifecycle() throws Exception {
        Java11PlatformModule module = new Java11PlatformModule();
        assertEquals("cortexj-platform-java11", module.name());
        assertEquals(11, module.featureVersion());
        assertFalse(module.supportsVirtualThreads());

        ExecutorService executor = module.createIoExecutor();
        try {
            AtomicBoolean ran = new AtomicBoolean(false);
            Future<?> future = executor.submit(() -> ran.set(true));
            future.get(5, TimeUnit.SECONDS);
            assertTrue(ran.get());
        } finally {
            executor.shutdownNow();
        }
    }
}
