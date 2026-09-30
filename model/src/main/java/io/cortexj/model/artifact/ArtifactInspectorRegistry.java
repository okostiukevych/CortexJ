package io.cortexj.model.artifact;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.spi.artifact.ArtifactInspection;
import io.cortexj.spi.artifact.ArtifactInspector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class ArtifactInspectorRegistry {

    private final List<ArtifactInspector> inspectors;

    public ArtifactInspectorRegistry() {
        this(loadDefaults());
    }

    public ArtifactInspectorRegistry(Iterable<ArtifactInspector> inspectors) {
        List<ArtifactInspector> list = new ArrayList<ArtifactInspector>();
        for (ArtifactInspector inspector : inspectors) {
            list.add(inspector);
        }
        list.sort(new Comparator<ArtifactInspector>() {
            @Override
            public int compare(ArtifactInspector a, ArtifactInspector b) {
                return a.getClass().getName().compareTo(b.getClass().getName());
            }
        });
        this.inspectors = Collections.unmodifiableList(list);
    }

    private static List<ArtifactInspector> loadDefaults() {
        List<ArtifactInspector> found = new ArrayList<ArtifactInspector>();
        found.add(new GgufArtifactInspector());
        found.add(new SafetensorsInspector());
        found.add(new OnnxLightInspector());
        try {
            for (ArtifactInspector inspector : ServiceLoader.load(ArtifactInspector.class)) {
                found.add(inspector);
            }
        } catch (Throwable ignored) {
        }
        return found;
    }

    public ArtifactInspector forFormat(ArtifactFormat format) {
        for (ArtifactInspector inspector : inspectors) {
            if (inspector.supports(format)) {
                return inspector;
            }
        }
        return null;
    }

    public ArtifactInspection inspectOrNull(ArtifactFormat format, java.nio.file.Path path) {
        ArtifactInspector inspector = forFormat(format);
        if (inspector == null) {
            return null;
        }
        try {
            return inspector.inspect(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Map<String, ArtifactInspection> inspectAll(Map<String, java.nio.file.Path> artifactsByPath) {
        Map<String, ArtifactInspection> result = new LinkedHashMap<String, ArtifactInspection>();
        for (Map.Entry<String, java.nio.file.Path> entry : artifactsByPath.entrySet()) {
            java.nio.file.Path path = entry.getValue();
            java.nio.file.Path fileName = path == null ? null : path.getFileName();
            if (fileName == null) {
                continue;
            }
            ArtifactFormat format = ArtifactFormat.fromFileName(fileName.toString());
            if (format == null) {
                continue;
            }
            ArtifactInspection inspection = inspectOrNull(format, path);
            if (inspection != null) {
                result.put(entry.getKey(), inspection);
            }
        }
        return result;
    }
}
