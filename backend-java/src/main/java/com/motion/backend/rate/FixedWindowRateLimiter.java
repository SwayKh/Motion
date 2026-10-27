package com.motion.backend.rate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-window limiter with the same first-request behavior as the rate.Limiter:
 * a new key is always admitted, even when the configured limit is zero.
 *
 * <p>Entries are purged whenever the limiter is used and the map has a hard cap, so rotating client
 * IPs cannot grow it without bound.</p>
 */
public final class FixedWindowRateLimiter {

    static final int MAX_TRACKED_KEYS = 10_000;

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        this.limit = limit;
        this.window = window;
        this.clock = clock;
    }

    public synchronized boolean allow(String key) {
        Instant now = clock.instant();
        purgeExpired(now);
        Entry entry = entries.get(key);
        if (entry == null || now.isAfter(entry.windowEnd())) {
            if (entry == null && entries.size() >= MAX_TRACKED_KEYS) {
                evictOldest();
            }
            entries.put(key, new Entry(1, now.plus(window)));
            return true;
        }
        if (entry.count() >= limit) {
            return false;
        }
        entry.count++;
        return true;
    }

    /** Exposed for tests that need to avoid real wall-clock waits. */
    synchronized void clear() {
        entries.clear();
    }

    /** Exposed for tests that assert bounded memory behavior. */
    synchronized int trackedKeys() {
        return entries.size();
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now.isAfter(iterator.next().getValue().windowEnd())) {
                iterator.remove();
            }
        }
    }

    private void evictOldest() {
        Iterator<String> iterator = entries.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static final class Entry {
        private int count;
        private final Instant windowEnd;

        private Entry(int count, Instant windowEnd) {
            this.count = count;
            this.windowEnd = windowEnd;
        }

        private int count() {
            return count;
        }

        private Instant windowEnd() {
            return windowEnd;
        }
    }
}
