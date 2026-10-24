package com.golda.patchertiktok;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

public class StreakTimingTest {
    private static final TimeZone ZONE = TimeZone.getTimeZone("Europe/Berlin");

    private static long at(int hour, int minute) {
        Calendar calendar = Calendar.getInstance(ZONE);
        calendar.clear();
        calendar.set(2026, Calendar.OCTOBER, 22, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    @Test
    public void decodesStoredValue() {
        assertEquals(-1, StreakTiming.minuteOfDay(0));
        assertEquals(0, StreakTiming.minuteOfDay(1));
        assertEquals(21 * 60, StreakTiming.minuteOfDay(21 * 60 + 1));
        assertEquals(-1, StreakTiming.minuteOfDay(24 * 60 + 2));
    }

    @Test
    public void withoutTimeSendsNow() {
        assertEquals(0, StreakTiming.sendAt(at(9, 0), at(23, 0), -1, ZONE));
    }

    @Test
    public void waitsForChosenTime() {
        assertEquals(at(21, 0), StreakTiming.sendAt(at(9, 0), at(23, 30), 21 * 60, ZONE));
    }

    @Test
    public void sendsOnceChosenTimePassed() {
        assertEquals(0, StreakTiming.sendAt(at(21, 5), at(23, 30), 21 * 60, ZONE));
    }

    @Test
    public void doesNotLetItBurnBeforeChosenTime() {
        assertEquals(at(19, 0), StreakTiming.sendAt(at(9, 0), at(20, 0), 21 * 60, ZONE));
        assertEquals(0, StreakTiming.sendAt(at(19, 30), at(20, 0), 21 * 60, ZONE));
    }
}
