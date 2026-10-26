package io.cortexj.platform.java17;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Java17PlatformModuleTest {

    @Test
    void moduleIdentityAndExecutorLifecycle() throws Exception {
        Java17PlatformModule module = new Java17PlatformModule();
        assertEquals("cortexj-platform-java17", module.name());
        assertEquals(17, module.featureVersion());
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
