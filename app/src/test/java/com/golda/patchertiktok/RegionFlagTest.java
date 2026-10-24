package com.golda.patchertiktok;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class RegionFlagTest {
    @Test public void buildsFlagsFromCountryCodes() {
        assertEquals("🇩🇪", RegionFlag.of("de"));
        assertEquals(null, RegionFlag.of("D1"));
    }

    @Test public void replacesTheFlagAddedBefore() {
        String shown = "5 d " + RegionFlag.of("NP");
        assertEquals("5 d", RegionFlag.without(shown));
        assertEquals("5 d", RegionFlag.without("5 d"));
    }
}
