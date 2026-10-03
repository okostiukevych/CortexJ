package io.cortexj.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

public final class SpiRegistries {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(SpiRegistries.class.getName());

    public interface IdAccessor<T> {
        String idOf(T provider);
    }

    public static <T> List<T> loadSorted(Class<T> type, IdAccessor<T> idAccessor, List<T> explicit) {
        Map<String, T> byId = new LinkedHashMap<String, T>();
        for (T candidate : resilientLoad(type)) {
            byId.put(idAccessor.idOf(candidate), candidate);
        }
        for (T candidate : explicit) {
            byId.put(idAccessor.idOf(candidate), candidate);
        }
        List<T> result = new ArrayList<T>(byId.values());
        result.sort(new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return idAccessor.idOf(a).compareTo(idAccessor.idOf(b));
            }
        });
        return Collections.unmodifiableList(result);
    }

    public static <T> List<T> resilientLoad(Class<T> type) {
        List<T> found = new ArrayList<T>();
        ServiceLoader<T> loader = ServiceLoader.load(type, SpiRegistries.class.getClassLoader());
        Iterator<T> iterator = loader.iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) {
                    break;
                }
                T candidate = iterator.next();
                if (candidate != null) {
                    found.add(candidate);
                }
            } catch (Throwable entryFailure) {
                LOG.warning("skipping unloadable " + type.getSimpleName()
                        + " entry: " + entryFailure);
            }
        }
        return found;
    }
}
