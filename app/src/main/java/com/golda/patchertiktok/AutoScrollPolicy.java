package com.golda.patchertiktok;

/** When a looping video counts as finished, kept apart from Android classes for tests. */
final class AutoScrollPolicy {
    private AutoScrollPolicy() { }

    /** The position went from the last moments of the video back to its start. */
    static boolean looped(long before, long now, long length) {
        if (before < 0 || now < 0 || length <= 0) return false;
        long tail = Math.max(1_200L, Math.min(3_000L, length / 5));
        return before >= length - tail && now < before && now <= 1_500L;
    }

    /**
     * How long a photo post stays: a few seconds per photo, at least five seconds in all, and no
     * longer than its music when the music length is known.
     */
    static long photoTime(int photos, long perPhoto, long music) {
        long time = Math.max(5_000L, photos * perPhoto + 1_000L);
        return music > 0 ? Math.min(time, music) : time;
    }
}
