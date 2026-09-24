package io.cortexj.planner;

public final class PlanReason {

    public enum Kind { POSITIVE, NEGATIVE, WARNING, INFO }

    private final ReasonCode code;
    private final Kind kind;
    private final String message;

    private PlanReason(ReasonCode code, Kind kind, String message) {
        this.code = code;
        this.kind = kind;
        this.message = message;
    }

    public static PlanReason positive(ReasonCode code, String message) {
        return new PlanReason(code, Kind.POSITIVE, message);
    }

    public static PlanReason negative(ReasonCode code, String message) {
        return new PlanReason(code, Kind.NEGATIVE, message);
    }

    public static PlanReason warning(ReasonCode code, String message) {
        return new PlanReason(code, Kind.WARNING, message);
    }

    public static PlanReason info(ReasonCode code, String message) {
        return new PlanReason(code, Kind.INFO, message);
    }

    public ReasonCode code() {
        return code;
    }

    public Kind kind() {
        return kind;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return (kind == Kind.POSITIVE ? "+ " : kind == Kind.NEGATIVE ? "- " : "! ") + message + " [" + code + "]";
    }
}
