package io.cortexj.spi.repository;

import io.cortexj.observability.PlatformEventListener;

import java.util.concurrent.ExecutorService;

public interface ResolutionContext {

    boolean isOffline();

    TokenSupplier tokenSupplier();

    ExecutorService executor();

    PlatformEventListener eventListener();

    static ResolutionContext create(boolean offline, TokenSupplier tokenSupplier, ExecutorService executor, PlatformEventListener listener) {
        return new ResolutionContext() {
            @Override
            public boolean isOffline() {
                return offline;
            }

            @Override
            public TokenSupplier tokenSupplier() {
                return tokenSupplier == null ? TokenSupplier.none() : tokenSupplier;
            }

            @Override
            public ExecutorService executor() {
                return executor;
            }

            @Override
            public PlatformEventListener eventListener() {
                return listener == null ? PlatformEventListener.NOOP : listener;
            }
        };
    }
}
