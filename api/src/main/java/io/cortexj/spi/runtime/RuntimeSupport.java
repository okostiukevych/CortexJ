package io.cortexj.spi.runtime;

import io.cortexj.planner.MemoryEstimate;
import io.cortexj.planner.PlanReason;
import io.cortexj.runtime.DeviceType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RuntimeSupport {

    public enum Level { FULL, PARTIAL, UNSUPPORTED }

    private final Level level;
    private final List<PlanReason> reasons;
    private final DeviceType proposedDevice;
    private final MemoryEstimate memoryEstimateOverride;
    private final Set<String> supportedQuantizations;

    private RuntimeSupport(Level level, List<PlanReason> reasons, DeviceType proposedDevice, MemoryEstimate memoryEstimateOverride, Set<String> supportedQuantizations) {
        this.level = level;
        this.reasons = Collections.unmodifiableList(new ArrayList<PlanReason>(reasons));
        this.proposedDevice = proposedDevice;
        this.memoryEstimateOverride = memoryEstimateOverride;
        this.supportedQuantizations = Collections.unmodifiableSet(new LinkedHashSet<String>(supportedQuantizations));
    }

    public static RuntimeSupport full(DeviceType proposedDevice, PlanReason... reasons) {
        return new RuntimeSupport(Level.FULL, reasonsList(reasons), proposedDevice, null, Collections.<String>emptySet());
    }

    public static RuntimeSupport partial(DeviceType proposedDevice, PlanReason... reasons) {
        return new RuntimeSupport(Level.PARTIAL, reasonsList(reasons), proposedDevice, null, Collections.<String>emptySet());
    }

    public static RuntimeSupport unsupported(PlanReason... reasons) {
        return new RuntimeSupport(Level.UNSUPPORTED, reasonsList(reasons), null, null, Collections.<String>emptySet());
    }

    private static List<PlanReason> reasonsList(PlanReason[] reasons) {
        List<PlanReason> list = new ArrayList<PlanReason>();
        Collections.addAll(list, reasons);
        return list;
    }

    public Level level() {
        return level;
    }

    public List<PlanReason> reasons() {
        return reasons;
    }

    public DeviceType proposedDevice() {
        return proposedDevice;
    }

    public MemoryEstimate memoryEstimateOverride() {
        return memoryEstimateOverride;
    }

    public Set<String> supportedQuantizations() {
        return supportedQuantizations;
    }

    public boolean isSupported() {
        return level != Level.UNSUPPORTED;
    }
}
