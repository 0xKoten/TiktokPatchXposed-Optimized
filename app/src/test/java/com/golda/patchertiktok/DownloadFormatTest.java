package com.golda.patchertiktok;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DownloadFormatTest {
    @Test public void usesShortSideForPortraitAndLandscapeLabels() {
        assertEquals("1080p / 4.2 MB", DownloadFormat.qualityLabel(1080, 1920, 4_200_000, false));
        assertEquals("1080p / ~4.2 MB", DownloadFormat.qualityLabel(1920, 1080, 4_200_000, true));
        assertEquals("720p", DownloadFormat.qualityLabel(720, 0, 0, false));
        assertEquals("Video", DownloadFormat.qualityLabel(0, 0, -1, false));
    }

    @Test public void labelDecimalDoesNotDependOnDeviceLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ru"));
            assertEquals("720p / 1.3 MB", DownloadFormat.qualityLabel(1280, 720, 1_250_000, false));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test public void bitrateEstimateUsesMillisecondsAndDoesNotOverflow() {
        assertEquals(4_200_000, DownloadFormat.estimatedBytes(1_120_000, 30_000));
        assertEquals(0, DownloadFormat.estimatedBytes(0, 30_000));
        assertEquals(0, DownloadFormat.estimatedBytes(1_120_000, -1));
        assertEquals(Long.MAX_VALUE, DownloadFormat.estimatedBytes(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test public void namesKeepCarouselOrderAndCannotEscapeTheMediaDirectory() {
        assertEquals("TikTok_123_batch_01.jpg", DownloadFormat.fileName("123", "batch", 1, "jpg"));
        assertEquals("TikTok_123_batch_35.webp", DownloadFormat.fileName("123", "batch", 35, "webp"));
        assertEquals("TikTok____path_a_b_01.jpg", DownloadFormat.fileName("../path", "a/b", -1, "jpg"));
        String name = DownloadFormat.fileName("x".repeat(1000), "batch", 1, "png");
        assertTrue(name.length() < 100);
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\\"));
    }

    @Test public void urlsRejectCredentialsAndNonNetworkSchemes() {
        assertTrue(DownloadFormat.webUrl("https://cdn.example.org/file?token=abc"));
        assertTrue(DownloadFormat.webUrl("http://cdn.example.org:8080/file"));
        for (String value : new String[]{null, "", "file:///tmp/file", "https:///file", "javascript:alert(1)",
                "https://user:secret@cdn.example.org/", "https://cdn.example.org:99999/"})
            assertFalse(String.valueOf(value), DownloadFormat.webUrl(value));
    }

    @Test public void keepsImageContainersAndRejectsErrorPages() {
        assertFormat(DownloadFormat.Kind.PHOTO, new byte[]{(byte) 255, (byte) 216, (byte) 255}, "image/jpeg", "jpg");
        assertFormat(DownloadFormat.Kind.PHOTO, "GIF89a".getBytes(StandardCharsets.US_ASCII), "image/gif", "gif");
        assertFormat(DownloadFormat.Kind.PHOTO, "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII), "image/webp", "webp");
        assertFormat(DownloadFormat.Kind.PHOTO, "0000ftypmif10000heic".getBytes(StandardCharsets.US_ASCII), "image/heic", "heic");
        assertFormat(DownloadFormat.Kind.PHOTO, "0000ftypavif".getBytes(StandardCharsets.US_ASCII), "image/avif", "avif");
        byte[] html = "<!doctype html><html>CDN error</html>".getBytes(StandardCharsets.US_ASCII);
        for (DownloadFormat.Kind kind : DownloadFormat.Kind.values()) assertNull(DownloadFormat.detect(kind, html, html.length));
        assertNull(DownloadFormat.detect(DownloadFormat.Kind.PHOTO, new byte[]{(byte) 255}, 1));
    }

    @Test public void audioContainersUseAudioExtensions() {
        assertFormat(DownloadFormat.Kind.AUDIO, "ID3".getBytes(StandardCharsets.US_ASCII), "audio/mpeg", "mp3");
        assertFormat(DownloadFormat.Kind.AUDIO, new byte[]{(byte) 255, (byte) 241}, "audio/aac", "aac");
        assertFormat(DownloadFormat.Kind.AUDIO, "OggS".getBytes(StandardCharsets.US_ASCII), "audio/ogg", "ogg");
        assertFormat(DownloadFormat.Kind.AUDIO, "0000ftypM4A ".getBytes(StandardCharsets.US_ASCII), "audio/mp4", "m4a");
        assertFormat(DownloadFormat.Kind.VIDEO, "0000ftypisom".getBytes(StandardCharsets.US_ASCII), "video/mp4", "mp4");
    }

    private static void assertFormat(DownloadFormat.Kind kind, byte[] bytes, String mime, String extension) {
        DownloadFormat.Format format = DownloadFormat.detect(kind, bytes, bytes.length);
        assertEquals(mime, format.mime);
        assertEquals(extension, format.extension);
    }
}
