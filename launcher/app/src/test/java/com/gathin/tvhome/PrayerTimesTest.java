package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** myQuran prayer dates and times shown under the clock. */
public class PrayerTimesTest {

    @Test
    public void acceptsIsoCalendarDates() {
        assertEquals("2026-10-21", PrayerTimes.dateKey("2026-10-21"));
        assertEquals("", PrayerTimes.dateKey("21-Okt-2026"));
        assertEquals("", PrayerTimes.dateKey("not a date"));
    }

    @Test
    public void readsTimesAndIndonesianPrayerNames() {
        assertEquals(781, PrayerTimes.minutes("13:01:00"));
        assertEquals(-1, PrayerTimes.minutes("25:00:00"));
        assertEquals("Subuh", PrayerTimes.NAMES[0]);
        assertEquals("Zuhur", PrayerTimes.NAMES[1]);
        assertEquals("Asar", PrayerTimes.NAMES[2]);
        assertEquals("Magrib", PrayerTimes.NAMES[3]);
        assertEquals("Isya", PrayerTimes.NAMES[4]);
    }

    @Test
    public void findsTheNextPrayerAndCountsDown() {
        int[] day = {349, 781, 978, 1140, 1210};
        assertEquals(0, PrayerTimes.next(day, 300));
        assertEquals(2, PrayerTimes.next(day, 781));
        assertEquals(-1, PrayerTimes.next(day, 1215));
        assertEquals("dalam 12 mnt", PrayerTimes.countdown(12));
        assertEquals("dalam 1 jam 5 mnt", PrayerTimes.countdown(65));
        assertEquals("dalam 2 jam", PrayerTimes.countdown(120));
        assertEquals("05:49", PrayerTimes.format(349, true));
        assertEquals("1:01", PrayerTimes.format(781, false));
    }
}
