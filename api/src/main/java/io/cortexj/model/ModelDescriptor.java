package io.cortexj.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ModelDescriptor {

    private final String canonicalId;
    private final String revision;
    private final String architecture;
    private final String license;
    private final long parameterCount;
    private final int contextLength;
    private final Set<TaskCapability> tasks;
    private final Set<Modality> modalities;
    private final List<ModelArtifact> artifacts;
    private final String trustNote;

    private ModelDescriptor(Builder builder) {
        this.canonicalId = builder.canonicalId;
        this.revision = builder.revision;
        this.architecture = builder.architecture;
        this.license = builder.license;
        this.parameterCount = builder.parameterCount;
        this.contextLength = builder.contextLength;
        this.tasks = Collections.unmodifiableSet(new LinkedHashSet<TaskCapability>(builder.tasks));
        this.modalities = Collections.unmodifiableSet(new LinkedHashSet<Modality>(builder.modalities));
        this.artifacts = Collections.unmodifiableList(new ArrayList<ModelArtifact>(builder.artifacts));
        this.trustNote = builder.trustNote;
    }

    public String canonicalId() {
        return canonicalId;
    }

    public String revision() {
        return revision;
    }

    public String architecture() {
        return architecture;
    }

    public String license() {
        return license;
    }

    public long parameterCount() {
        return parameterCount;
    }

    public int contextLength() {
        return contextLength;
    }

    public Set<TaskCapability> tasks() {
        return tasks;
    }

    public Set<Modality> modalities() {
        return modalities;
    }

    public List<ModelArtifact> artifacts() {
        return artifacts;
    }

    public String trustNote() {
        return trustNote;
    }

    public boolean supports(TaskCapability task) {
        return tasks.contains(task);
    }

    public ModelDescriptor withRevision(String newRevision) {
        return toBuilder().revision(newRevision).build();
    }

    public Builder toBuilder() {
        Builder builder = new Builder(canonicalId);
        builder.revision = revision;
        builder.architecture = architecture;
        builder.license = license;
        builder.parameterCount = parameterCount;
        builder.contextLength = contextLength;
        builder.tasks.addAll(tasks);
        builder.modalities.addAll(modalities);
        builder.artifacts.addAll(artifacts);
        builder.trustNote = trustNote;
        return builder;
    }

    public static Builder builder(String canonicalId) {
        return new Builder(canonicalId);
    }

    public static final class Builder {

        private final String canonicalId;
        private String revision;
        private String architecture;
        private String license;
        private long parameterCount;
        private int contextLength;
        private final Set<TaskCapability> tasks = new LinkedHashSet<TaskCapability>();
        private final Set<Modality> modalities = new LinkedHashSet<Modality>();
        private final List<ModelArtifact> artifacts = new ArrayList<ModelArtifact>();
        private String trustNote;

        private Builder(String canonicalId) {
            if (canonicalId == null || canonicalId.trim().isEmpty()) {
                throw new IllegalArgumentException("Model canonical id must not be empty");
            }
            this.canonicalId = canonicalId.trim();
        }

        public Builder revision(String value) {
            this.revision = value;
            return this;
        }

        public Builder architecture(String value) {
            this.architecture = value;
            return this;
        }

        public Builder license(String value) {
            this.license = value;
            return this;
        }

        public Builder parameterCount(long value) {
            this.parameterCount = value;
            return this;
        }

        public Builder contextLength(int value) {
            this.contextLength = value;
            return this;
        }

        public Builder task(TaskCapability task) {
            if (task != null) {
                tasks.add(task);
            }
            return this;
        }

        public Builder tasks(Iterable<TaskCapability> values) {
            for (TaskCapability task : values) {
                task(task);
            }
            return this;
        }

        public Builder modality(Modality modality) {
            if (modality != null) {
                modalities.add(modality);
            }
            return this;
        }

        public Builder artifact(ModelArtifact artifact) {
            if (artifact != null) {
                artifacts.add(artifact);
            }
            return this;
        }

        public Builder artifacts(Iterable<? extends ModelArtifact> values) {
            for (ModelArtifact artifact : values) {
                artifact(artifact);
            }
            return this;
        }

        public Builder trustNote(String note) {
            this.trustNote = note;
            return this;
        }

        public ModelDescriptor build() {
            return new ModelDescriptor(this);
        }
    }
}
