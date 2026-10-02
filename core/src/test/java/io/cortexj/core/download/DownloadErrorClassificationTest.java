package io.cortexj.core.download;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileSystemException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadErrorClassificationTest {

    @Test
    void noSpaceLeftMessageIsClassifiedAsDiskFull() {
        assertTrue(DownloadManager.isDiskFull(new IOException("No space left on device")),
                "ENOSPC must be recognized as a terminal (non-retryable) failure");
    }

    @Test
    void fileSystemExceptionIsClassifiedAsDiskFull() {
        assertTrue(DownloadManager.isDiskFull(new FileSystemException("/cache/downloads-partial/x.part")),
                "filesystem-level failures must be terminal");
    }

    @Test
    void wrappedNoSpaceLeftCauseIsClassifiedAsDiskFull() {
        assertTrue(DownloadManager.isDiskFull(new IOException("write failed",
                new IOException("No space left on device"))),
                "the cause chain must be inspected for ENOSPC");
    }

    @Test
    void ordinaryIoErrorsRemainRetryable() {
        assertFalse(DownloadManager.isDiskFull(new IOException("Connection reset")),
                "network-level errors must stay retryable");
        assertFalse(DownloadManager.isDiskFull(new IOException("Read timed out",
                new IOException("socket closed"))),
                "network-level errors must stay retryable");
        assertFalse(DownloadManager.isDiskFull(null), "a null error must not be classified as disk full");
    }
}
