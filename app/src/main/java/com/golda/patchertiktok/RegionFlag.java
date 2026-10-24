package com.golda.patchertiktok;

import java.util.Locale;
import java.util.regex.Pattern;

/** Country flags as emoji, kept apart from Android classes for tests. */
final class RegionFlag {
    /** A flag appended earlier: a space and two regional indicator symbols at the end. */
    private static final Pattern APPENDED = Pattern.compile(" [\\x{1F1E6}-\\x{1F1FF}]{2}$");

    private RegionFlag() { }

    /** "DE" → 🇩🇪, built from regional indicator symbols. */
    static String of(String iso) {
        if (iso == null || iso.length() != 2) return null;
        String code = iso.toUpperCase(Locale.ROOT);
        char a = code.charAt(0);
        char b = code.charAt(1);
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return null;
        return new String(Character.toChars(0x1F1E6 + a - 'A')) + new String(Character.toChars(0x1F1E6 + b - 'A'));
    }

    /** The text without a flag appended earlier, so a reused label never collects two. */
    static String without(String text) {
        return APPENDED.matcher(text).replaceFirst("");
    }
}
