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
 * tryRetain/release serialize on the per-key lock (GitHub #6): a retain racing
 * a release either stays registered or the orphaned endpoint is stopped
 * exactly once - the registry entry is removed only at refcount zero.
 */
final class SharedEndpointRegistry {

    private final ConcurrentHashMap<String, SharedEndpoint> endpoints =
            new ConcurrentHashMap<String, SharedEndpoint>();
    private final ConcurrentHashMap<String, Object> keyLocks =
            new ConcurrentHashMap<String, Object>();

    SharedEndpoint acquire(String key, LlamaServerLauncher launcher, Path artifactPath)
            throws IOException {
        return acquire(key, launcher, artifactPath,
                java.util.Collections.<String, Object>emptyMap());
    }

    SharedEndpoint acquire(String key, LlamaServerLauncher launcher, Path artifactPath,
                           java.util.Map<String, Object> serverArgs)
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
                LlamaServerEndpoint fresh = launcher.launch(artifactPath, serverArgs);
                SharedEndpoint shared = new SharedEndpoint(fresh, key, endpoints, keyLocks, lock);
                endpoints.put(key, shared);
                return shared;
            }
        }
    }

    int liveEndpointCount() {
        return endpoints.size();
    }

    int liveKeyLockCount() {
        return keyLocks.size();
    }

    /**
     * Reference-counted wrapper: baseUrl() delegates; stop() delegates only
     * when called by the last detaching handle (refcount reaches zero).
     * tryRetain and release serialize on the per-key lock so the registry
     * entry is removed exactly when the refcount reaches zero and the stop
     * fires exactly once; the per-key lock object is dropped with the entry
     * so the lock map cannot grow without bound.
     */
    static final class SharedEndpoint implements LlamaServerEndpoint {

        private final LlamaServerEndpoint delegate;
        private final String key;
        private final ConcurrentHashMap<String, SharedEndpoint> registry;
        private final ConcurrentHashMap<String, Object> keyLocks;
        private final Object keyLock;
        private final AtomicInteger refcount = new AtomicInteger(1);
        private boolean unregistered;

        SharedEndpoint(LlamaServerEndpoint delegate, String key,
                       ConcurrentHashMap<String, SharedEndpoint> registry,
                       ConcurrentHashMap<String, Object> keyLocks, Object keyLock) {
            this.delegate = delegate;
            this.key = key;
            this.registry = registry;
            this.keyLocks = keyLocks;
            this.keyLock = keyLock;
        }

        String key() {
            return key;
        }

        /**
         * Attach another handle. Returns false if the endpoint was already
         * released and removed from the registry (the caller must acquire a
         * fresh one); the acquire loop performs that retry. The refcount is
         * only incremented while the endpoint is still registered, so a
         * release can never race past an increment into a lost stop().
         */
        boolean tryRetain() {
            synchronized (keyLock) {
                if (unregistered || registry.get(key) != this) {
                    return false;
                }
                refcount.incrementAndGet();
                return true;
            }
        }

        /**
         * Detach a handle. The registry entry is removed only when the
         * refcount reaches zero, so a concurrent tryRetain either retains a
         * still-registered endpoint or observes the removal after this
         * release stopped the process exactly once. The per-key lock is
         * deliberately NOT removed (audit #112): removing it let an acquirer
         * hold the old lock while a second acquirer created a fresh one, and
         * both would see no endpoint and launch a duplicate llama-server.
         */
        void release() {
            boolean stopNow = false;
            synchronized (keyLock) {
                if (refcount.decrementAndGet() <= 0 && !unregistered) {
                    unregistered = true;
                    registry.remove(key, this);
                    stopNow = true;
                }
            }
            if (stopNow) {
                delegate.stop();
            }
        }

        /**
         * Evict a dead or unreachable endpoint immediately even if other
         * handles still hold references, ensuring the next load spawns a
         * fresh process and {@code delegate.stop()} fires at most once.
         */
        void evict() {
            boolean stopNow = false;
            synchronized (keyLock) {
                if (!unregistered) {
                    unregistered = true;
                    registry.remove(key, this);
                    stopNow = true;
                }
            }
            if (stopNow) {
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
