package com.golda.patchertiktok;

/**
 * Colors for the module's own surfaces. {@link #of} matches TikTok's settings screens, for the
 * pieces drawn inside TikTok's panels; {@link #settings} is the module's own monochrome look
 * for its settings screen.
 */
final class Palette {
    final boolean dark;
    /** The settings screen's monochrome look: flat toggles without shadow or check mark. */
    final boolean mono;
    final int background;
    final int statusBar;
    final int card;
    final int pressed;
    final int textPrimary;
    final int textSecondary;
    final int textTertiary;
    final int chevron;
    final int field;
    final int accent;
    final int trackOn;
    final int trackOff;
    final int thumb;
    /** Quiet fill under small marks. */
    final int deep;
    /** Color of small marks drawn on {@link #deep}. */
    final int frost;
    /** The restart bar: inverted, so it is the one solid shape on the screen. */
    final int warm;
    final int onWarm;
    /** A thin line, for outlined marks. */
    final int hairline;

    private Palette(boolean dark) {
        this.dark = dark;
        mono = false;
        if (dark) {
            background = 0xFF000000;
            statusBar = 0xFF121212;
            // Not one of TikTok's greys, so the AMOLED theme leaves the cards visible on black.
            card = 0xFF1C1C1E;
            pressed = 0xFF2C2C2C;
            textPrimary = 0xFFF6F6F6;
            textSecondary = 0xFF999999;
            textTertiary = 0xFF787878;
            chevron = 0xFF787878;
            field = 0xFF2A2A2A;
            trackOff = 0xFF484848;
        } else {
            background = 0xFFF5F5F5;
            statusBar = 0xFFFFFFFF;
            card = 0xFFFFFFFF;
            pressed = 0xFFEDEDED;
            textPrimary = 0xFF161823;
            textSecondary = 0xFF757575;
            textTertiary = 0xFF8A8B91;
            chevron = 0xFFA8A8A8;
            field = 0xFFF1F1F2;
            trackOff = 0xFFE0E0E0;
        }
        accent = 0xFFFE2C55;
        trackOn = 0xFF20D5EC;
        thumb = 0xFFFFFFFF;
        deep = dark ? 0xFF1B2A4A : 0xFFE3ECFB;
        frost = dark ? 0xFFA9C4F5 : 0xFF2F5FD0;
        warm = dark ? 0xFFF2B866 : 0xFFFBEFD9;
        onWarm = dark ? 0xFF3A2A12 : 0xFF8A5510;
        hairline = 0;
    }

    /**
     * The settings screen, monochrome: one quiet card per group on a near-black page, dot-matrix
     * titles. "On" is the text color itself, so the only hue on the screen is the user's own
     * accent, and only when they chose one ({@code accent != 0}).
     */
    private Palette(boolean dark, boolean amoled, int accent) {
        this.dark = dark;
        mono = true;
        if (dark) {
            background = amoled ? 0xFF000000 : 0xFF0A0A0B;
            statusBar = background;
            card = amoled ? 0xFF0F1012 : 0xFF141518;
            pressed = 0xFF1E1F23;
            textPrimary = 0xFFEDEEF0;
            textSecondary = 0xFF8A8F98;
            textTertiary = 0xFF5E636D;
            chevron = 0xFF5E636D;
            field = card;
            trackOff = 0xFF26282D;
            thumb = 0xFF6B717C;
            hairline = 0x14FFFFFF;
        } else {
            background = 0xFFF2F2F4;
            statusBar = background;
            card = 0xFFFFFFFF;
            pressed = 0xFFEBEBED;
            textPrimary = 0xFF0D0D0F;
            textSecondary = 0xFF6B6F78;
            textTertiary = 0xFF9DA1A9;
            chevron = 0xFF9DA1A9;
            field = card;
            trackOff = 0xFFDCDDE1;
            thumb = 0xFFFFFFFF;
            hairline = 0x14000000;
        }
        deep = field;
        frost = textSecondary;
        warm = textPrimary;
        onWarm = background;
        this.accent = accent != 0 ? accent : textPrimary;
        trackOn = this.accent;
    }

    private static final Palette DARK = new Palette(true);
    private static final Palette LIGHT = new Palette(false);

    static Palette of(boolean dark) { return dark ? DARK : LIGHT; }

    /**
     * The settings screen palette. {@code accent} is the user's own color, or 0 for none; a grey
     * or near-black choice counts as none, since "on" would then look like "off".
     */
    static Palette settings(boolean dark, boolean amoled, int accent) {
        if (accent != 0) {
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(accent, hsv);
            if (hsv[1] < 0.25f || hsv[2] < 0.35f) accent = 0;
        }
        return new Palette(dark, amoled, accent);
    }
}
