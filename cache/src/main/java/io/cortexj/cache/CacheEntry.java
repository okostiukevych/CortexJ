package io.cortexj.cache;

import java.nio.file.Path;

public final class CacheEntry {

    private final String key;
    private final Path path;
    private final long sizeBytes;
    private final long lastModifiedMillis;

    public CacheEntry(String key, Path path, long sizeBytes, long lastModifiedMillis) {
        this.key = key;
        this.path = path;
        this.sizeBytes = sizeBytes;
        this.lastModifiedMillis = lastModifiedMillis;
    }

    public String key() {
        return key;
    }

    public Path path() {
        return path;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public long lastModifiedMillis() {
        return lastModifiedMillis;
    }
}
