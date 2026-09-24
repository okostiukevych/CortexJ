package io.cortexj.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ModelCapabilities {

    private final Set<TaskCapability> tasks;
    private final boolean streamingSupported;
    private final boolean structuredOutputSupported;
    private final int maxConcurrentRequests;

    private ModelCapabilities(Set<TaskCapability> tasks, boolean streamingSupported, boolean structuredOutputSupported, int maxConcurrentRequests) {
        this.tasks = Collections.unmodifiableSet(new LinkedHashSet<TaskCapability>(tasks));
        this.streamingSupported = streamingSupported;
        this.structuredOutputSupported = structuredOutputSupported;
        this.maxConcurrentRequests = maxConcurrentRequests;
    }

    public static ModelCapabilities of(Set<TaskCapability> tasks, boolean streamingSupported, boolean structuredOutputSupported, int maxConcurrentRequests) {
        return new ModelCapabilities(tasks, streamingSupported, structuredOutputSupported, maxConcurrentRequests);
    }

    public static ModelCapabilities ofTasks(TaskCapability... tasks) {
        Set<TaskCapability> set = new LinkedHashSet<TaskCapability>();
        Collections.addAll(set, tasks);
        return new ModelCapabilities(set, false, false, 1);
    }

    public Set<TaskCapability> tasks() {
        return tasks;
    }

    public boolean supports(TaskCapability task) {
        return tasks.contains(task);
    }

    public boolean isStreamingSupported() {
        return streamingSupported;
    }

    public boolean isStructuredOutputSupported() {
        return structuredOutputSupported;
    }

    public int maxConcurrentRequests() {
        return maxConcurrentRequests;
    }
}
