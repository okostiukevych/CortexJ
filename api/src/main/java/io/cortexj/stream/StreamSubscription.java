package io.cortexj.stream;

public interface StreamSubscription {

    void cancel();

    default void request(long tokens) {
    }

    default boolean isCancelled() {
        return false;
    }
}
