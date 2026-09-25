package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.ErrorCode;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TrustPolicy;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustPolicyTest {

    static final class RecordingListener implements PlatformEventListener {

        final List<PlatformEvent> events = new ArrayList<PlatformEvent>();

        @Override
        public void onEvent(PlatformEvent event) {
            events.add(event);
        }
    }

    private CortexJ platform(TrustPolicy policy, RecordingListener listener) {
        return CortexJ.builder()
                .runtimeProvider(new io.cortexj.core.EndToEndLoadTest.FakeRuntimeProvider())
                .repositoryProvider(new io.cortexj.core.EndToEndLoadTest.FakeRepository())
                .eventListener(listener)
                .trustPolicy(policy)
                .build();
    }

    @Test
    void trustedOnlyBlocksUntrustedModelWithActionableError() {
        RecordingListener listener = new RecordingListener();
        CortexJ platform = platform(TrustPolicy.TRUSTED_ONLY, listener);
        try {
            ModelLoadException failure = assertThrows(ModelLoadException.class,
                    () -> platform.models().load(ModelReference.of("e2e/test-model")));
            assertEquals(ErrorCode.SEC_POLICY_BLOCKED, failure.code());
            assertTrue(!failure.remediation().isEmpty());
        } finally {
            platform.close();
        }
    }

    @Test
    void allowUnsignedWithWarningLoadsAndRecordsEvent() {
        RecordingListener listener = new RecordingListener();
        CortexJ platform = platform(TrustPolicy.ALLOW_UNSIGNED_WITH_WARNING, listener);
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            handle.close();
            boolean warned = false;
            for (PlatformEvent event : listener.events) {
                if (event.level() == PlatformEvent.Level.WARNING
                        && event.message().contains("untrusted model")) {
                    warned = true;
                    break;
                }
            }
            assertTrue(warned, "a warning event must be recorded for untrusted models");
        } finally {
            platform.close();
        }
    }
}
