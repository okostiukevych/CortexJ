package io.cortexj.spi.conversion;

import io.cortexj.planner.ReasonCode;

public final class ConversionSupport {

    private final boolean supported;
    private final ReasonCode reasonCode;
    private final String message;

    private ConversionSupport(boolean supported, ReasonCode reasonCode, String message) {
        this.supported = supported;
        this.reasonCode = reasonCode;
        this.message = message;
    }

    public static ConversionSupport supported() {
        return new ConversionSupport(true, null, null);
    }

    public static ConversionSupport unsupported(ReasonCode reason, String message) {
        return new ConversionSupport(false, reason, message);
    }

    public boolean isSupported() {
        return supported;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }

    public String message() {
        return message;
    }
}
