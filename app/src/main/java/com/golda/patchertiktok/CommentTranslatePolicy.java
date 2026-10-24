package com.golda.patchertiktok;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Android-free language eligibility and bounded, monotonic dispatch accounting. */
final class CommentTranslatePolicy {
    private static final Map<String, String> LANGUAGES = languages();

    private CommentTranslatePolicy() { }

    static String language(String value) {
        if (value == null) return "";
        String tag = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!tag.matches("[a-z]{2,3}(-[a-z0-9]{2,8})*")) return "";
        String primary = tag.split("-", 2)[0];
        return LANGUAGES.getOrDefault(primary, "");
    }

    static Set<String> exclusions(String input) {
        Set<String> result = new LinkedHashSet<>();
        if (input != null) {
            for (String token : input.split("[,;\\s]+")) {
                String language = language(token);
                if (!language.isEmpty()) result.add(language);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static boolean eligible(String source, String app, Set<String> excluded) {
        String from = language(source);
        String to = language(app);
        return !from.isEmpty() && !to.isEmpty() && !from.equals(to) && !excluded.contains(from);
    }

    static int rate(int value) { return Math.max(1, Math.min(5, value)); }

    static String key(String id, String target) {
        if (id == null || id.trim().isEmpty() || language(target).isEmpty()) return "";
        return id + '\0' + target.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Map<String, String> languages() {
        Map<String, String> values = new HashMap<>();
        for (String code : Locale.getISOLanguages()) {
            String canonical = code.equals("in") ? "id" : code.equals("iw") ? "he"
                    : code.equals("ji") ? "yi" : code;
            values.put(code, canonical);
            try { values.put(new Locale(code).getISO3Language(), canonical); }
            catch (java.util.MissingResourceException ignored) { }
        }
        values.put("in", "id");
        values.put("iw", "he");
        values.put("ji", "yi");
        return Collections.unmodifiableMap(values);
    }

    /**
     * At most capacity IDs are remembered for ttlMs. A full table skips new IDs rather than
     * evicting protection for recently attempted IDs. All times are elapsedRealtime millis.
     * Spacing is strictly greater than 1000/N, including the ends of a one-second window.
     */
    static final class Gate {
        private final int capacity;
        private final long ttlMs;
        private final LinkedHashMap<String, Long> attempts = new LinkedHashMap<>();
        private long nextDispatch;
        private long lastDispatch = -1;

        Gate(int capacity, long ttlMs) {
            if (capacity < 1 || ttlMs < 1) throw new IllegalArgumentException("Invalid gate bounds");
            this.capacity = capacity;
            this.ttlMs = ttlMs;
        }

        synchronized boolean seen(String key, long now) {
            expire(now);
            return key.isEmpty() || attempts.containsKey(key) || attempts.size() >= capacity;
        }

        synchronized long delay(long now) { return Math.max(0, nextDispatch - now); }

        synchronized long delay(long now, int perSecond) {
            long limit = lastDispatch < 0 ? nextDispatch
                    : Math.max(nextDispatch, lastDispatch + 1000 / rate(perSecond) + 1);
            return Math.max(0, limit - now);
        }

        synchronized boolean acquire(String key, long now, int perSecond) {
            if (seen(key, now) || delay(now, perSecond) > 0) return false;
            attempts.put(key, now);
            lastDispatch = now;
            nextDispatch = now + 1000 / rate(perSecond) + 1;
            return true;
        }

        synchronized void remember(String key, long now) {
            expire(now);
            if (!key.isEmpty() && (attempts.containsKey(key) || attempts.size() < capacity)) {
                // Refreshing an existing entry must preserve timestamp order for expiry.
                attempts.remove(key);
                attempts.put(key, now);
            }
        }

        private void expire(long now) {
            Iterator<Map.Entry<String, Long>> iterator = attempts.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Long> entry = iterator.next();
                if (now - entry.getValue() < ttlMs) break;
                iterator.remove();
            }
        }
    }
}
