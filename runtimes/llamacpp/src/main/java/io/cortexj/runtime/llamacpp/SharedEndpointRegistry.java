package io.cortexj.runtime.llamacpp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared-endpoint registry with refcounting (audit P0-6): one llama-server
 * process per (artifact, server-relevant options) instead of one per handle.
 * Handles attach on load and detach on close; the process is stopped through
 * the existing stop() escalation ladder only when the last handle detaches.
 */
final class SharedEndpointRegistry {

    private final ConcurrentHashMap<String, SharedEndpoint> endpoints =
            new ConcurrentHashMap<String, SharedEndpoint>();
    private final ConcurrentHashMap<String, Object> keyLocks =
            new ConcurrentHashMap<String, Object>();

    SharedEndpoint acquire(String key, LlamaServerLauncher launcher, Path artifactPath)
            throws IOException {
        Object lock = keyLocks.computeIfAbsent(key, new java.util.function.Function<String, Object>() {
            @Override
            public Object apply(String ignored) {
                return new Object();
            }
        });
        for (;;) {
            SharedEndpoint existing = endpoints.get(key);
            if (existing != null && existing.tryRetain()) {
                return existing;
            }
            synchronized (lock) {
                existing = endpoints.get(key);
                if (existing != null && existing.tryRetain()) {
                    return existing;
                }
                LlamaServerEndpoint fresh = launcher.launch(artifactPath);
                SharedEndpoint shared = new SharedEndpoint(fresh, key, endpoints);
                endpoints.put(key, shared);
                return shared;
            }
        }
    }

    int liveEndpointCount() {
        return endpoints.size();
    }

    /**
     * Reference-counted wrapper: baseUrl() delegates; stop() delegates only
     * when called by the last detaching handle (refcount reaches zero).
     */
    static final class SharedEndpoint implements LlamaServerEndpoint {

        private final LlamaServerEndpoint delegate;
        private final String key;
        private final ConcurrentHashMap<String, SharedEndpoint> registry;
        private final AtomicInteger refcount = new AtomicInteger(1);

        SharedEndpoint(LlamaServerEndpoint delegate, String key,
                       ConcurrentHashMap<String, SharedEndpoint> registry) {
            this.delegate = delegate;
            this.key = key;
            this.registry = registry;
        }

        String key() {
            return key;
        }

        /**
         * Attach another handle. Returns false if the endpoint was already
         * released and removed from the registry (the caller must acquire a
         * fresh one); the acquire loop performs that retry.
         */
        boolean tryRetain() {
            refcount.incrementAndGet();
            if (registry.get(key) == this) {
                return true;
            }
            refcount.decrementAndGet();
            return false;
        }

        /**
         * Detach a handle. Removes the endpoint from the registry first, so a
         * concurrent tryRetain can never resurrect a stopped process. Stops
         * the underlying endpoint (escalation ladder) when the last handle
         * detaches.
         */
        void release() {
            registry.remove(key, this);
            if (refcount.decrementAndGet() == 0) {
                delegate.stop();
            }
        }

        @Override
        public String baseUrl() {
            return delegate.baseUrl();
        }

        @Override
        public void stop() {
            delegate.stop();
        }
    }
}
