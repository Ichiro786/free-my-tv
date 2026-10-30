package io.freemytv;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A map that keeps at most `max` entries, dropping the least recently used one (for image caches: they can't grow
 * without bound on a TV with little memory). Evicted bitmaps are not recycled here, since one may still be on screen;
 * the garbage collector frees them once nothing draws them. Not thread-safe: callers synchronize as before.
 */
final class Lru<K, V> extends LinkedHashMap<K, V> {
    private final int max;

    Lru(int max) {
        super(16, 0.75f, true);
        this.max = max;
    }

    @Override protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > max;
    }
}
