package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwallowedFailureLoggingTest {

    @TempDir
    Path workDir;

    static final class ThrowingCloseRuntimeProvider extends ModelHandleCloseRaceTest.CountingRuntimeProvider {

        @Override
        public io.cortexj.spi.runtime.ModelRuntime open(io.cortexj.spi.runtime.RuntimeOpenRequest request) {
            return new io.cortexj.spi.runtime.ModelRuntime() {
                @Override
                public io.cortexj.runtime.RuntimeId runtimeId() {
                    return ThrowingCloseRuntimeProvider.this.id();
                }

                @Override
                public io.cortexj.spi.runtime.RuntimeModel load(io.cortexj.spi.runtime.RuntimeModelRequest loadRequest) {
                    return new ModelHandleCloseRaceTest.CountingRuntimeModel();
                }

                @Override
                public void close() {
                    throw new IllegalStateException("native session close exploded");
                }
            };
        }
    }

    private static final class CapturingHandler extends Handler {
        final List<LogRecord> records = new ArrayList<LogRecord>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static CapturingHandler install(Logger logger, Level level) {
        CapturingHandler handler = new CapturingHandler();
        logger.addHandler(handler);
        logger.setLevel(level);
        return handler;
    }

    private static void uninstall(Logger logger, CapturingHandler handler, Level oldLevel) {
        logger.removeHandler(handler);
        logger.setLevel(oldLevel);
    }

    @Test
    void failingRuntimeCloseSurfacesInDebugLogsAndExecutionContinues() {
        ThrowingCloseRuntimeProvider provider = new ThrowingCloseRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
        Logger logger = Logger.getLogger(DefaultModelHandle.class.getName());
        Level oldLevel = logger.getLevel();
        CapturingHandler handler = install(logger, Level.FINE);
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            handle.close();

            assertEquals(ModelLifecycleState.CLOSED, handle.state(),
                    "close() must complete even when the runtime close throws");
            boolean surfaced = false;
            for (LogRecord record : handler.records) {
                if (record.getMessage() != null && record.getMessage().contains("runtime close failed")) {
                    surfaced = true;
                    assertTrue(record.getThrown() != null,
                            "the swallowed failure must carry the original exception");
                }
            }
            assertTrue(surfaced,
                    "a failing runtime close must surface in the debug logs (non-fatally)");
        } finally {
            uninstall(logger, handler, oldLevel);
        }
    }

    @Test
    void failingListenerSurfacesInDebugLogsAndDeliveryContinues() {
        Logger logger = Logger.getLogger(EventBus.class.getName());
        Level oldLevel = logger.getLevel();
        CapturingHandler handler = install(logger, Level.FINE);
        try {
            EventBus bus = new EventBus(Arrays.<io.cortexj.observability.PlatformEventListener>asList(
                    new EventBusIsolationTest.ThrowingListener()));
            bus.asListener().onEvent(new io.cortexj.observability.PlatformEvent(
                    io.cortexj.observability.PlatformEvent.Type.MODEL_LOADED,
                    io.cortexj.observability.PlatformEvent.Level.INFO, "event", null,
                    System.currentTimeMillis()));

            assertTrue(!handler.records.isEmpty(),
                    "the swallowed listener failure must surface in the debug logs");
            assertTrue(handler.records.get(0).getThrown() != null,
                    "the log record must carry the original listener exception");
        } finally {
            uninstall(logger, handler, oldLevel);
        }
    }

    @Test
    void debugLoggingIsQuietByDefault() {
        Logger logger = Logger.getLogger(EventBus.class.getName());
        Level oldLevel = logger.getLevel();
        CapturingHandler handler = install(logger, Level.INFO);
        try {
            EventBus bus = new EventBus(Arrays.<io.cortexj.observability.PlatformEventListener>asList(
                    new EventBusIsolationTest.ThrowingListener()));
            bus.asListener().onEvent(new io.cortexj.observability.PlatformEvent(
                    io.cortexj.observability.PlatformEvent.Type.MODEL_LOADED,
                    io.cortexj.observability.PlatformEvent.Level.INFO, "event", null,
                    System.currentTimeMillis()));
            assertTrue(handler.records.isEmpty(),
                    "swallowed failures must stay invisible at the default INFO level");
        } finally {
            uninstall(logger, handler, oldLevel);
        }
    }
}
