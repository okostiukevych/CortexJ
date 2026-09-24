package io.cortexj.spi.policy;

import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;

public final class PolicyDecision {

    public enum Outcome { ALLOW, REJECT, ADJUST }

    private final Outcome outcome;
    private final double scoreAdjustment;
    private final ReasonCode reasonCode;
    private final String message;

    private PolicyDecision(Outcome outcome, double scoreAdjustment, ReasonCode reasonCode, String message) {
        this.outcome = outcome;
        this.scoreAdjustment = scoreAdjustment;
        this.reasonCode = reasonCode;
        this.message = message == null ? (reasonCode == null ? "" : reasonCode.defaultText()) : message;
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(Outcome.ALLOW, 0.0d, null, null);
    }

    public static PolicyDecision reject(ReasonCode reason, String message) {
        return new PolicyDecision(Outcome.REJECT, 0.0d, reason, message);
    }

    public static PolicyDecision adjust(double scoreDelta, ReasonCode reason, String message) {
        return new PolicyDecision(Outcome.ADJUST, scoreDelta, reason, message);
    }

    public Outcome outcome() {
        return outcome;
    }

    public double scoreAdjustment() {
        return scoreAdjustment;
    }

    public ReasonCode reasonCode() {
        return reasonCode;
    }

    public String message() {
        return message;
    }

    public boolean isRejection() {
        return outcome == Outcome.REJECT;
    }

    public PlanReason asReason() {
        return outcome == Outcome.REJECT
                ? PlanReason.negative(reasonCode, message)
                : PlanReason.positive(reasonCode, message);
    }
}
