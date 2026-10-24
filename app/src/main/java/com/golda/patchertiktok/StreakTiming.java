package com.golda.patchertiktok;

import java.util.Calendar;
import java.util.TimeZone;

/** When a streak that is fading gets its message, for the "send at" setting. */
final class StreakTiming {
    /** A streak that would burn out before the chosen time still gets its message this long before the end. */
    static final long SAFETY_MS = 60 * 60_000L;

    private StreakTiming() { }

    /** {@link Prefs#STREAK_TIME} value: 0 means "as soon as it starts fading", else 1 + minute of the day. */
    static int minuteOfDay(long stored) {
        return stored <= 0 || stored > 24 * 60 ? -1 : (int) stored - 1;
    }

    /**
     * 0 to send now; otherwise the moment to send. Without a chosen time it is always now. With one,
     * a fading streak waits for today's chosen time, unless its end comes first.
     */
    static long sendAt(long now, long endMillis, int minuteOfDay, TimeZone zone) {
        if (minuteOfDay < 0) return 0;
        Calendar calendar = Calendar.getInstance(zone);
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, minuteOfDay / 60);
        calendar.set(Calendar.MINUTE, minuteOfDay % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        long target = calendar.getTimeInMillis();
        if (now >= target) return 0;
        long latest = endMillis - SAFETY_MS;
        if (latest <= now) return 0;
        return Math.min(target, latest);
    }
}
