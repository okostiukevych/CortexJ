package io.cortexj.model.artifact;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.spi.artifact.ArtifactInspection;
import io.cortexj.spi.artifact.ArtifactInspector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class ArtifactInspectorRegistry {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(ArtifactInspectorRegistry.class.getName());

    private static final int CACHE_LIMIT = 64;

    private final List<ArtifactInspector> inspectors;
    private final Map<java.nio.file.Path, CachedInspection> cache;

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
        this.cache = Collections.synchronizedMap(new LinkedHashMap<java.nio.file.Path, CachedInspection>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<java.nio.file.Path, CachedInspection> eldest) {
                return size() > CACHE_LIMIT;
            }
        });
    }

    private static List<ArtifactInspector> loadDefaults() {
        List<ArtifactInspector> found = new ArrayList<ArtifactInspector>();
        found.add(new GgufArtifactInspector());
        found.add(new SafetensorsInspector());
        found.add(new OnnxLightInspector());
        ServiceLoader<ArtifactInspector> loader = ServiceLoader.load(ArtifactInspector.class);
        java.util.Iterator<ArtifactInspector> iterator = loader.iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                ArtifactInspector candidate = iterator.next();
                if (candidate != null) {
                    found.add(candidate);
                }
            } catch (Throwable entryFailure) {
                LOG.warning("skipping unloadable ArtifactInspector service entry: " + entryFailure);
            }
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
        if (inspector == null || path == null) {
            return null;
        }
        try {
            return inspectCached(inspector, path);
        } catch (RuntimeException e) {
            LOG.warning("artifact inspection failed for " + path + " (" + format + "): " + e);
            return null;
        }
    }

    private ArtifactInspection inspectCached(ArtifactInspector inspector, java.nio.file.Path path) {
        long size = 0L;
        long lastModified = 0L;
        boolean statable = false;
        try {
            java.nio.file.attribute.BasicFileAttributes attributes =
                    java.nio.file.Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            if (attributes.isRegularFile()) {
                size = attributes.size();
                lastModified = attributes.lastModifiedTime().toMillis();
                statable = true;
            }
        } catch (IOException e) {
            statable = false;
        }
        if (statable) {
            CachedInspection cached = cache.get(path);
            if (cached != null && cached.size == size && cached.lastModified == lastModified) {
                return cached.inspection;
            }
        }
        ArtifactInspection inspection = inspector.inspect(path);
        if (statable) {
            cache.put(path, new CachedInspection(size, lastModified, inspection));
        }
        return inspection;
    }

    private static final class CachedInspection {

        final long size;
        final long lastModified;
        final ArtifactInspection inspection;

        CachedInspection(long size, long lastModified, ArtifactInspection inspection) {
            this.size = size;
            this.lastModified = lastModified;
            this.inspection = inspection;
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
