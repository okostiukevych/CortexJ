package io.cortexj.core;

import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class EventBusIsolationTest {

    static final class ThrowingListener implements PlatformEventListener {
        @Override
        public void onEvent(PlatformEvent event) {
            throw new IllegalStateException("listener exploded on " + event.type());
        }
    }

    static final class CountingListener implements PlatformEventListener {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public void onEvent(PlatformEvent event) {
            count.incrementAndGet();
        }
    }

    static final class RecordingListener implements PlatformEventListener {
        final List<PlatformEvent.Type> seen = new CopyOnWriteArrayList<PlatformEvent.Type>();

        @Override
        public void onEvent(PlatformEvent event) {
            seen.add(event.type());
        }
    }

    @Test
    void publishIsolatesThrowingListenerAndDeliversToTheRest() {
        CountingListener first = new CountingListener();
        ThrowingListener middle = new ThrowingListener();
        CountingListener last = new CountingListener();
        EventBus multi = new EventBus(Arrays.<PlatformEventListener>asList(first, middle, last));
        multi.info(PlatformEvent.Type.PLATFORM_STARTED, "hello");
        assertEquals(1, first.count.get());
        assertEquals(1, last.count.get(),
                "both remaining listeners must be delivered despite one throwing");
    }

    @Test
    void asListenerIsolatesThrowingListenerAndDeliversToTheRest() {
        CountingListener first = new CountingListener();
        ThrowingListener middle = new ThrowingListener();
        CountingListener last = new CountingListener();
        EventBus multi = new EventBus(Arrays.<PlatformEventListener>asList(first, middle, last));
        PlatformEventListener adapted = multi.asListener();
        assertDoesNotThrow(() -> adapted.onEvent(new PlatformEvent(
                PlatformEvent.Type.DOWNLOAD_COMPLETED, PlatformEvent.Level.INFO, "done", null,
                System.currentTimeMillis())),
                "asListener() must isolate a throwing listener exactly like publish()");
        assertEquals(1, first.count.get());
        assertEquals(1, last.count.get());
    }

    @Test
    void throwingListenerDoesNotStarveTheOthers() {
        ThrowingListener thrower = new ThrowingListener();
        RecordingListener follower = new RecordingListener();
        EventBus pair = new EventBus(Arrays.<PlatformEventListener>asList(thrower, follower));
        PlatformEventListener adapted = pair.asListener();
        for (int i = 0; i < 5; i++) {
            adapted.onEvent(new PlatformEvent(PlatformEvent.Type.MODEL_LOADED,
                    PlatformEvent.Level.INFO, "event " + i, null, System.currentTimeMillis()));
        }
        assertEquals(5, follower.seen.size(),
                "a permanently throwing listener must not starve the others");
    }
}
