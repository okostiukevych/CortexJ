package io.cortexj.springai;

import io.cortexj.ErrorCode;
import io.cortexj.error.ConfigurationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiSupportTest {

    @Test
    void probePassesOnSpringAi2Classpath() {
        // the test classpath carries Spring AI 2.0.1: the 2.0-only default exists
        assertTrue(SpringAiSupport.isSpringAi2Present());
        SpringAiSupport.requireChatAdapterSupport(); // must not throw
    }

    @Test
    void absentProbeFailsFastWithConfCodeAndRemediation() {
        ConfigurationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                ConfigurationException.class,
                () -> SpringAiSupport.require(false));

        assertTrue(failure.getMessage().contains("Spring AI 2.0.x or newer"));
        assertTrue(failure.getMessage().contains("ChatModel.getOptions()"));
        List<String> remediation = failure.remediation();
        assertEquals(1, remediation.size(), "remediation must be present");
        assertTrue(remediation.get(0).contains("EOL"));
        assertEquals(ErrorCode.CONF_INVALID, failure.code());
    }
}
