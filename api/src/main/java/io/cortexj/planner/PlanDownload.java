package io.cortexj.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PlanDownload {

    private final String artifactId;
    private final String source;
    private final long sizeBytes;

    public PlanDownload(String artifactId, String source, long sizeBytes) {
        this.artifactId = artifactId;
        this.source = source;
        this.sizeBytes = sizeBytes;
    }

    public String artifactId() {
        return artifactId;
    }

    public String source() {
        return source;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public static List<PlanDownload> emptyList() {
        return Collections.<PlanDownload>emptyList();
    }

    public static List<PlanDownload> singleton(PlanDownload download) {
        List<PlanDownload> list = new ArrayList<PlanDownload>(1);
        list.add(download);
        return list;
    }

    @Override
    public String toString() {
        return artifactId + " <- " + source + " (" + sizeBytes + " bytes)";
    }
}
