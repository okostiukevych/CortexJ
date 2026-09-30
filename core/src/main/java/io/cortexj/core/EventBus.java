package io.cortexj.core;

import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

public final class EventBus {

    public static final int ASYNC_QUEUE_CAPACITY = 10_000;

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(EventBus.class.getName());

    private final List<PlatformEventListener> listeners = new CopyOnWriteArrayList<PlatformEventListener>();
    private final java.util.concurrent.ArrayBlockingQueue<PlatformEvent> asyncQueue;
    private volatile boolean closed;

    public EventBus(List<PlatformEventListener> registered) {
        this(registered, false);
    }

    /**
     * Async delivery mode (audit L-9): events are dispatched on a dedicated
     * daemon thread through a bounded queue - a slow listener cannot stall the
     * load/inference path. Ordering is lost on queue overflow (events are
     * dropped and logged at FINE level); the queue drains gracefully on close.
     */
    public EventBus(List<PlatformEventListener> registered, boolean asyncDelivery) {
        if (registered != null) {
            listeners.addAll(registered);
        }
        if (asyncDelivery) {
            this.asyncQueue = new java.util.concurrent.ArrayBlockingQueue<PlatformEvent>(ASYNC_QUEUE_CAPACITY);
            Thread dispatcher = io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-events")
                    .newThread(new Runnable() {
                        @Override
                        public void run() {
                            while (true) {
                                try {
                                    PlatformEvent event = EventBus.this.asyncQueue.poll(500,
                                            java.util.concurrent.TimeUnit.MILLISECONDS);
                                    if (event == null) {
                                        if (EventBus.this.closed) {
                                            return;
                                        }
                                        continue;
                                    }
                                    dispatch(event);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    return;
                                } catch (RuntimeException failure) {
                                    LOG.log(java.util.logging.Level.FINE,
                                            "async event dispatch failed; dispatcher continues", failure);
                                }
                            }
                        }
                    });
            dispatcher.start();
        } else {
            this.asyncQueue = null;
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
        if (asyncQueue != null) {
            if (!asyncQueue.offer(event)) {
                LOG.log(java.util.logging.Level.FINE,
                        "event dropped: the async event queue is full: " + event.type());
            }
            return;
        }
        dispatch(event);
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
                if (asyncQueue != null) {
                    if (!asyncQueue.offer(event)) {
                        LOG.log(java.util.logging.Level.FINE,
                                "event dropped: the async event queue is full: " + event.type());
                    }
                    return;
                }
                dispatch(event);
            }
        };
    }

    public void close() {
        closed = true;
        // the dispatcher daemon thread exits via the closed flag + poll timeout,
        // so the queue drains gracefully before the thread stops
    }

    private void dispatch(PlatformEvent event) {
        for (PlatformEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException failure) {
                LOG.log(java.util.logging.Level.FINE,
                        "event listener threw on " + event.type() + "; isolated", failure);
            }
        }
    }
}
