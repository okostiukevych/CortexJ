package io.cortexj.core;

import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

public final class EventBus {

    private final List<PlatformEventListener> listeners = new CopyOnWriteArrayList<PlatformEventListener>();

    public EventBus(List<PlatformEventListener> registered) {
        if (registered != null) {
            listeners.addAll(registered);
        }
    }

    public void publish(PlatformEvent.Type type, PlatformEvent.Level level, String message, Map<String, String> attributes) {
        if (listeners.isEmpty()) {
            return;
        }
        Map<String, String> safeAttributes = attributes == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(attributes);
        PlatformEvent event = new PlatformEvent(type, level, message, safeAttributes, System.currentTimeMillis());
        for (PlatformEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ignored) {
            }
        }
    }

    public void info(PlatformEvent.Type type, String message) {
        publish(type, PlatformEvent.Level.INFO, message, null);
    }

    public void warn(PlatformEvent.Type type, String message) {
        publish(type, PlatformEvent.Level.WARNING, message, null);
    }

    public void error(PlatformEvent.Type type, String message) {
        publish(type, PlatformEvent.Level.ERROR, message, null);
    }

    public PlatformEventListener asListener() {
        return new PlatformEventListener() {
            @Override
            public void onEvent(PlatformEvent event) {
                for (PlatformEventListener listener : listeners) {
                    listener.onEvent(event);
                }
            }
        };
    }
}
