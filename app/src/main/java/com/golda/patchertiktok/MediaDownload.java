package com.golda.patchertiktok;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Background-only streaming into scoped storage; failed attempts remove their pending rows. */
final class MediaDownload {
    private MediaDownload() { }

    static boolean save(Context context, DownloadModels.Item item, String id, String batch, int index) {
        for (String url : item.urls) {
            try {
                saveUrl(context.getContentResolver(), item.kind, url, id, batch, index);
                return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private static void saveUrl(ContentResolver resolver, DownloadFormat.Kind kind, String url,
            String id, String batch, int index) throws IOException {
        HttpURLConnection connection = null;
        Uri row = null;
        boolean published = false;
        try {
            connection = open(url);
            long length = connection.getContentLengthLong();
            long limit = kind == DownloadFormat.Kind.PHOTO ? 64L << 20 : 2L << 30;
            if (length > limit) throw new IOException("Media too large");
            try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream(), 64 * 1024)) {
                input.mark(64);
                byte[] head = new byte[64];
                int count = 0;
                while (count < head.length) {
                    int read = input.read(head, count, head.length - count);
                    if (read < 0) break;
                    count += read;
                }
                input.reset();
                DownloadFormat.Format format = DownloadFormat.detect(kind, head, count);
                if (format == null) throw new IOException("Unknown media container");
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, DownloadFormat.fileName(id, batch, index, format.extension));
                values.put(MediaStore.MediaColumns.MIME_TYPE, format.mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, directory(kind) + "/TikTok");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                row = resolver.insert(collection(kind), values);
                if (row == null) throw new IOException("MediaStore insert failed");
                try (OutputStream output = resolver.openOutputStream(row, "w")) {
                    if (output == null) throw new IOException("MediaStore stream unavailable");
                    copy(input, output, length, limit);
                }
            }
            ContentValues publish = new ContentValues();
            publish.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(row, publish, null, null) != 1) throw new IOException("MediaStore publish failed");
            published = true;
        } finally {
            if (row != null && !published) {
                try { resolver.delete(row, null, null); } catch (RuntimeException ignored) { }
            }
            if (connection != null) {
                try { connection.disconnect(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private static HttpURLConnection open(String address) throws IOException {
        String current = address;
        for (int hop = 0; hop <= 5; hop++) {
            if (!DownloadFormat.webUrl(current)) throw new IOException("Unsupported URL");
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            boolean accepted = false;
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(20_000);
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) {
                    accepted = true;
                    return connection;
                }
                if (status != 301 && status != 302 && status != 303 && status != 307 && status != 308)
                    throw new IOException("HTTP " + status);
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Redirect without location");
                current = new URL(new URL(current), location).toExternalForm();
            } finally {
                if (!accepted) connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static void copy(InputStream input, OutputStream output, long expected, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        long deadline = SystemClock.elapsedRealtime() + 10 * 60_000;
        for (int read; (read = input.read(buffer)) >= 0;) {
            if (Thread.currentThread().isInterrupted() || SystemClock.elapsedRealtime() > deadline)
                throw new IOException("Download interrupted");
            total += read;
            if (total > limit) throw new IOException("Media too large");
            output.write(buffer, 0, read);
        }
        if (total == 0 || (expected >= 0 && total != expected)) throw new IOException("Incomplete media");
    }

    static String directory(DownloadFormat.Kind kind) {
        if (kind == DownloadFormat.Kind.PHOTO) return Environment.DIRECTORY_PICTURES;
        if (kind == DownloadFormat.Kind.AUDIO) return Environment.DIRECTORY_MUSIC;
        return Environment.DIRECTORY_MOVIES;
    }

    private static Uri collection(DownloadFormat.Kind kind) {
        if (kind == DownloadFormat.Kind.PHOTO) return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        if (kind == DownloadFormat.Kind.AUDIO) return MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }
}
