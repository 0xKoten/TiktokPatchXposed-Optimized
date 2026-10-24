package com.golda.patchertiktok;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * Strips share tracking from TikTok links when they are copied.
 *
 * TikTok's "Copy link" puts a short vt.tiktok.com link on the clipboard. The short code itself
 * identifies who shared the video, so it is expanded in the background to the plain video link
 * and the clipboard is updated, as long as it still holds the copied text.
 */
final class CleanLinks {
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIKTOK_HOST = Pattern.compile(
            "https?://([a-z0-9-]+\\.)*(tiktok\\.com|tiktokv\\.com|tiktokv\\.eu|musical\\.ly)(/|$)",
            Pattern.CASE_INSENSITIVE);
    /** Short share links: vt./vm.tiktok.com/CODE and tiktok.com/t/CODE. */
    private static final Pattern SHORT = Pattern.compile(
            "https?://(?:(?:vt|vm)\\.tiktok\\.com|(?:www\\.)?tiktok\\.com/t)/[A-Za-z0-9_-]+/?",
            Pattern.CASE_INSENSITIVE);
    /** Videos, photo posts and profiles need nothing from the query string. */
    private static final Pattern CONTENT_PATH = Pattern.compile(
            "https?://(?:www\\.|m\\.)?tiktok\\.com/@[^/?#]+(?:/(?:video|photo)/\\d+)?/?(?=[?#]|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> TRACKING = new HashSet<>(Arrays.asList(
            "_r", "_t", "_d", "_svg", "share_item_id", "share_link_id", "share_app_id", "share_app_name",
            "share_scene", "share_uid", "share_author_id", "share_iid", "sharer_language", "timestamp", "tt_from",
            "u_code", "ug_btm", "ugbiz_name", "user_id", "sec_user_id", "source", "utm_source", "utm_medium",
            "utm_campaign", "utm_term", "utm_content", "enter_from", "enter_method", "iid", "device_id", "did",
            "aid", "web_id", "webid", "mstoken", "region", "sender_device", "sender_web_id", "is_from_webapp",
            "is_copy_url", "checksum", "enable_checksum", "sec_uid", "social_share_type", "social_sharing",
            "language", "refer", "preview_pb"));
    private static final ExecutorService RESOLVER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "tiktok-clean-links");
        thread.setDaemon(true);
        return thread;
    });

    private CleanLinks() { }

    static void install() {
        Hooks.hookAll(ClipboardManager.class, "setPrimaryClip", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (!Prefs.on(Prefs.CLEAN_LINKS) || param.args.length == 0
                        || !(param.args[0] instanceof ClipData)) return;
                ClipData clip = (ClipData) param.args[0];
                if (clip.getItemCount() == 0) return;
                List<String> cleaned = new ArrayList<>(clip.getItemCount());
                boolean changed = false;
                for (int index = 0; index < clip.getItemCount(); index++) {
                    CharSequence text = clip.getItemAt(index).getText();
                    if (text == null) return;
                    String value = cleanText(text.toString());
                    changed |= !value.contentEquals(text);
                    cleaned.add(value);
                }
                if (changed) param.args[0] = replace(clip, cleaned);
                if (clip.getItemCount() == 1 && SHORT.matcher(cleaned.get(0)).find()) {
                    expandLater(new WeakReference<>((ClipboardManager) param.thisObject), cleaned.get(0));
                }
            }
        });
    }

    private static ClipData replace(ClipData clip, List<String> texts) {
        ClipData replacement = new ClipData(clip.getDescription(), new ClipData.Item(texts.get(0)));
        for (int index = 1; index < texts.size(); index++) replacement.addItem(new ClipData.Item(texts.get(index)));
        return replacement;
    }

    /** Resolves short links off the main thread, then swaps the clipboard if nothing else was copied. */
    private static void expandLater(WeakReference<ClipboardManager> clipboard, String copied) {
        RESOLVER.execute(() -> {
            Map<String, String> expanded = new LinkedHashMap<>();
            Matcher matcher = SHORT.matcher(copied);
            while (matcher.find()) {
                String target = resolve(matcher.group());
                if (target != null) expanded.put(matcher.group(), target);
            }
            if (expanded.isEmpty()) return;
            String result = copied;
            for (Map.Entry<String, String> entry : expanded.entrySet()) result = result.replace(entry.getKey(), entry.getValue());
            String finalText = result;
            new Handler(Looper.getMainLooper()).post(() -> {
                ClipboardManager manager = clipboard.get();
                if (manager == null || !Prefs.on(Prefs.CLEAN_LINKS)) return;
                try {
                    ClipData current = manager.getPrimaryClip();
                    CharSequence text = current == null || current.getItemCount() != 1 ? null : current.getItemAt(0).getText();
                    // The user may have copied something else meanwhile; never overwrite that.
                    if (text == null || !copied.contentEquals(text)) return;
                    manager.setPrimaryClip(new ClipData(current.getDescription(), new ClipData.Item(finalText)));
                    RuntimeLog.log("clean links: expanded " + expanded.size() + " short link(s)");
                } catch (RuntimeException error) {
                    RuntimeLog.log("clean links: clipboard unavailable " + error.getClass().getSimpleName());
                }
            });
        });
    }

    /** The clean destination of a short link, or null if it cannot be resolved right now. */
    private static String resolve(String shortLink) {
        String current = shortLink;
        for (int hop = 0; hop < 3; hop++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(current).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5_000);
                connection.setReadTimeout(5_000);
                int code = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if (code < 300 || code >= 400 || location == null) return null;
                current = new URL(new URL(current), location).toString();
            } catch (Exception error) {
                RuntimeLog.log("clean links: short link not resolved " + error.getClass().getSimpleName());
                return null;
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (!SHORT.matcher(current).matches()) {
                // Expired or broken codes lead to the home page; keep the short link then.
                String clean = cleanUrl(current);
                int fragment = clean.indexOf('#');
                return CONTENT_PATH.matcher(fragment < 0 ? clean : clean.substring(0, fragment)).matches() ? clean : null;
            }
        }
        return null;
    }

    static String cleanText(String text) {
        Matcher matcher = URL.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(cleanUrl(matcher.group())));
        matcher.appendTail(out);
        return out.toString();
    }

    static String cleanUrl(String url) {
        if (!TIKTOK_HOST.matcher(url).find()) return url;
        int query = url.indexOf('?');
        if (query < 0) return url;
        int hash = url.indexOf('#', query);
        String base = url.substring(0, query);
        String fragment = hash < 0 ? "" : url.substring(hash);
        if (CONTENT_PATH.matcher(base).matches()) return base + fragment;
        String params = hash < 0 ? url.substring(query + 1) : url.substring(query + 1, hash);
        StringBuilder kept = new StringBuilder();
        for (String pair : params.split("&")) {
            if (pair.isEmpty()) continue;
            int equals = pair.indexOf('=');
            String key = (equals < 0 ? pair : pair.substring(0, equals)).toLowerCase(Locale.ROOT);
            if (TRACKING.contains(key)) continue;
            kept.append(kept.length() == 0 ? '?' : '&').append(pair);
        }
        return base + kept + fragment;
    }
}
