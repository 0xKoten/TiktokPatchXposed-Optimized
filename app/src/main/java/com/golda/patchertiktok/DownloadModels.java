package com.golda.patchertiktok;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Snapshots readable TikTok models without changing playback or download addresses. */
final class DownloadModels {
    static final class Item {
        final DownloadFormat.Kind kind;
        final List<String> urls;
        final int width;
        final int height;
        final long bytes;
        final long bitrate;
        final boolean estimated;

        Item(DownloadFormat.Kind kind, List<String> urls, int width, int height,
                long bytes, long bitrate, boolean estimated) {
            this.kind = kind;
            this.urls = Collections.unmodifiableList(new ArrayList<>(urls));
            this.width = width;
            this.height = height;
            this.bytes = bytes;
            this.bitrate = bitrate;
            this.estimated = estimated;
        }

        String label() { return DownloadFormat.qualityLabel(width, height, bytes, estimated); }
    }

    private DownloadModels() { }

    static List<Item> videos(Object aweme) {
        Object video = Reflect.call(aweme, "getVideo");
        Map<String, Item> choices = new LinkedHashMap<>();
        long duration = number(Reflect.call(video, "getDuration"));
        Object rates = Reflect.call(video, "getRawBitRate");
        if (!(rates instanceof List)) rates = Reflect.call(video, "getBitRate");
        for (Object rate : list(rates)) {
            Object address = Reflect.call(rate, "getPlayAddr");
            long bitrate = number(Reflect.call(rate, "getBitRate"));
            long size = number(Reflect.call(rate, "getSize"));
            if (size <= 0) size = number(Reflect.call(address, "getSize"));
            boolean estimated = size <= 0;
            if (estimated) size = DownloadFormat.estimatedBytes(bitrate, duration);
            add(choices, item(DownloadFormat.Kind.VIDEO, address,
                    number(Reflect.call(rate, "getVideoWidth")), number(Reflect.call(rate, "getVideoHeight")),
                    size, bitrate, estimated));
        }
        // The base and codec alternatives can contain a variant absent from bitRateList.
        for (String getter : new String[]{"getPlayAddr", "getPlayAddrH264", "getPlayAddrBytevc1"}) {
            Object address = Reflect.call(video, getter);
            add(choices, item(DownloadFormat.Kind.VIDEO, address,
                    number(Reflect.call(video, "getWidth")), number(Reflect.call(video, "getHeight")),
                    number(Reflect.call(address, "getSize")), 0, false));
        }
        List<Item> sorted = new ArrayList<>(choices.values());
        sorted.sort(Comparator.comparingLong((Item i) -> (long) i.width * i.height)
                .thenComparingLong(i -> i.bitrate).thenComparingLong(i -> i.bytes).reversed());
        return sorted;
    }

    static Item audio(Object aweme) {
        Object address = Reflect.call(Reflect.call(aweme, "getMusic"), "getPlayUrl");
        return item(DownloadFormat.Kind.AUDIO, address, 0, 0,
                number(Reflect.call(address, "getSize")), 0, false);
    }

    static List<?> photoImages(Object aweme) {
        Object info = Reflect.call(aweme, "getPhotoModeImageInfo");
        List<?> images = list(Reflect.call(info, "getImageList"));
        if (images.isEmpty()) images = list(Reflect.call(info, "getPhotoModeImageList"));
        if (images.isEmpty()) images = list(Reflect.call(aweme, "getImageInfos"));
        return images;
    }

    /** All-or-nothing preflight: an absent URL leaves TikTok's original action available. */
    static List<Item> photos(List<?> images) {
        List<Item> result = new ArrayList<>();
        for (Object image : images) {
            Item best = item(DownloadFormat.Kind.PHOTO, photoAddress(image), 0, 0, 0, 0, false);
            if (best == null) return Collections.emptyList();
            result.add(best);
        }
        return result;
    }

    /** One picture at {@code address} (an address model), or null without a usable URL. */
    static Item picture(Object address) {
        return address == null ? null : item(DownloadFormat.Kind.PHOTO, address, 0, 0, 0, 0, false);
    }

    /** Names of the full-size picture in TikTok's photo models, best first; the clean one has no watermark. */
    private static final String[] PHOTO_FIELDS = {"displayImageNoWatermark", "displayImage", "urlModel", "labelLarge"};
    /** Smaller or altered copies that are never the picture itself. */
    private static final String[] NOT_PHOTO = {"thumbnail", "translated", "dynamic", "cover", "preview", "watermark"};

    /**
     * The address of one carousel picture. Named fields come first; when TikTok renames them, the
     * largest address-like field that is not a thumbnail or a translated copy is taken.
     */
    static Object photoAddress(Object image) {
        if (image == null) return null;
        if (!urls(image).isEmpty()) return image;
        for (String name : PHOTO_FIELDS) {
            Object value = Reflect.get(image, name);
            if (!urls(value).isEmpty()) return value;
        }
        Object best = null;
        long bestArea = -1;
        for (Class<?> type = image.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || excluded(field.getName())) continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(image);
                } catch (Throwable ignored) {
                    continue;
                }
                if (value == null || value instanceof String || value instanceof List || urls(value).isEmpty()) continue;
                long area = number(Reflect.get(value, "width")) * number(Reflect.get(value, "height"));
                if (area > bestArea) {
                    best = value;
                    bestArea = area;
                }
            }
        }
        if (best != null) return best;
        // Watermarked copies are still better than nothing.
        for (String name : new String[]{"ownerWatermarkImage", "userWatermarkImage"}) {
            Object value = Reflect.get(image, name);
            if (!urls(value).isEmpty()) return value;
        }
        return null;
    }

    private static boolean excluded(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String part : NOT_PHOTO) if (lower.contains(part)) return true;
        return false;
    }

    private static Item item(DownloadFormat.Kind kind, Object address, long width, long height,
            long bytes, long bitrate, boolean estimated) {
        List<String> urls = urls(address);
        if (urls.isEmpty()) return null;
        if (width <= 0) width = number(Reflect.get(address, "width"));
        if (height <= 0) height = number(Reflect.get(address, "height"));
        if (bytes <= 0) bytes = number(Reflect.get(address, "size"));
        return new Item(kind, urls, dimension(width), dimension(height), Math.max(0, bytes),
                Math.max(0, bitrate), estimated);
    }

    private static void add(Map<String, Item> choices, Item item) {
        if (item == null) return;
        Item previous = choices.get(item.urls.get(0));
        if (previous == null || (previous.bytes == 0 && item.bytes > 0)) choices.put(item.urls.get(0), item);
    }

    private static List<String> urls(Object address) {
        Object raw = Reflect.get(address, "urlList");
        if (!(raw instanceof List)) raw = Reflect.call(address, "getUrlList");
        List<String> urls = new ArrayList<>();
        for (Object value : list(raw)) {
            if (value instanceof String && DownloadFormat.webUrl((String) value) && !urls.contains(value))
                urls.add((String) value);
        }
        return urls;
    }

    private static List<?> list(Object value) {
        return value instanceof List ? new ArrayList<>((List<?>) value) : Collections.emptyList();
    }

    private static long number(Object value) { return value instanceof Number ? ((Number) value).longValue() : 0; }

    private static int dimension(long value) { return value > 0 && value <= Integer.MAX_VALUE ? (int) value : 0; }
}
