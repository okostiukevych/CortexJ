package io.cortexj.observability;

public interface PlatformEventListener {

    void onEvent(PlatformEvent event);

    PlatformEventListener NOOP = new PlatformEventListener() {
        @Override
        public void onEvent(PlatformEvent event) {
        }
    };
}
