package com.golda.patchertiktok;

import java.net.URI;
import java.util.Locale;

/** Pure download labels, safe names and container signatures. */
final class DownloadFormat {
    enum Kind { VIDEO, AUDIO, PHOTO }

    static final class Format {
        final String mime;
        final String extension;

        Format(String mime, String extension) {
            this.mime = mime;
            this.extension = extension;
        }
    }

    private DownloadFormat() { }

    static String qualityLabel(int width, int height, long bytes, boolean estimated) {
        String resolution = resolution(width, height);
        return bytes <= 0 ? resolution : resolution + " / " + size(bytes, estimated);
    }

    /** "1080p" by the shorter side, so portrait and landscape read the same. */
    static String resolution(int width, int height) {
        int shortSide = width > 0 && height > 0 ? Math.min(width, height) : Math.max(width, height);
        return shortSide > 0 ? shortSide + "p" : "Video";
    }

    /** "1080 × 1440", or empty when TikTok gave no size. */
    static String dimensions(int width, int height) {
        return width > 0 && height > 0 ? width + " × " + height : "";
    }

    /** Decimal megabytes; "~" marks a size worked out from bitrate and duration. Empty if unknown. */
    static String size(long bytes, boolean estimated) {
        if (bytes <= 0) return "";
        return (estimated ? "~" : "") + String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
    }

    static long estimatedBytes(long bitsPerSecond, long durationMillis) {
        if (bitsPerSecond <= 0 || durationMillis <= 0) return 0;
        double bytes = bitsPerSecond / 8.0 * (durationMillis / 1000.0);
        return bytes >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) bytes;
    }

    static String fileName(String id, String batch, int index, String extension) {
        return "TikTok_" + safePart(id) + '_' + safePart(batch) + '_'
                + String.format(Locale.ROOT, "%02d", Math.max(1, index)) + '.' + safePart(extension);
    }

    private static String safePart(String value) {
        String clean = value == null ? "" : value.replaceAll("[^A-Za-z0-9_-]", "_");
        return clean.isEmpty() ? "unknown" : clean.substring(0, Math.min(64, clean.length()));
    }

    static boolean webUrl(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && !uri.getHost().isEmpty() && uri.getUserInfo() == null
                    && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Never publish an HTML error page or label a GIF/HEIF as a JPEG. */
    static Format detect(Kind kind, byte[] head, int length) {
        if (kind == Kind.PHOTO) {
            if (length >= 3 && (head[0] & 255) == 255 && (head[1] & 255) == 216
                    && (head[2] & 255) == 255) return new Format("image/jpeg", "jpg");
            if (length >= 8 && (head[0] & 255) == 137 && matches(head, length, 1, "PNG\r\n\u001a\n"))
                return new Format("image/png", "png");
            if (matches(head, length, 0, "GIF87a") || matches(head, length, 0, "GIF89a"))
                return new Format("image/gif", "gif");
            if (matches(head, length, 0, "RIFF") && matches(head, length, 8, "WEBP"))
                return new Format("image/webp", "webp");
            if (matches(head, length, 4, "ftyp")) {
                // Compatible brands can follow the major brand (for example mif1 + heic).
                for (int offset = 8; offset + 4 <= length; offset += 4) {
                    if (matches(head, length, offset, "avif") || matches(head, length, offset, "avis"))
                        return new Format("image/avif", "avif");
                    if (matches(head, length, offset, "heic") || matches(head, length, offset, "heix")
                            || matches(head, length, offset, "hevc") || matches(head, length, offset, "hevx"))
                        return new Format("image/heic", "heic");
                }
            }
        } else if (matches(head, length, 4, "ftyp")) {
            return kind == Kind.AUDIO ? new Format("audio/mp4", "m4a") : new Format("video/mp4", "mp4");
        } else if (kind == Kind.AUDIO) {
            if (matches(head, length, 0, "ID3") || (length >= 2 && (head[0] & 255) == 255
                    && (head[1] & 0xe0) == 0xe0 && (head[1] & 0x06) != 0))
                return new Format("audio/mpeg", "mp3");
            if (length >= 2 && (head[0] & 255) == 255 && (head[1] & 0xf6) == 0xf0)
                return new Format("audio/aac", "aac");
            if (matches(head, length, 0, "OggS")) return new Format("audio/ogg", "ogg");
            if (matches(head, length, 0, "fLaC")) return new Format("audio/flac", "flac");
            if (matches(head, length, 0, "RIFF") && matches(head, length, 8, "WAVE"))
                return new Format("audio/wav", "wav");
        }
        return null;
    }

    private static boolean matches(byte[] bytes, int length, int offset, String value) {
        if (offset + value.length() > length) return false;
        for (int i = 0; i < value.length(); i++) if ((bytes[offset + i] & 255) != value.charAt(i)) return false;
        return true;
    }
}
