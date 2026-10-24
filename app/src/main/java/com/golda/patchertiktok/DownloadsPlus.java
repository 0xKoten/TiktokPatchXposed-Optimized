package com.golda.patchertiktok;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Download action replacements discovered from literals and readable model signatures, never stacks. */
public final class DownloadsPlus {
    /** Video replacement is opt-in; disabling the picker automatically saves the best available video. */
    public static volatile boolean videoDownloads;
    public static volatile boolean qualityPicker = true;
    public static volatile boolean saveAllPhotos;

    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    private static final String VIDEO = "com.ss.android.ugc.aweme.feed.model.Video";
    private static final String URL_MODEL = "com.ss.android.ugc.aweme.base.model.UrlModel";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Set<String> ACTIVE = Collections.synchronizedSet(new HashSet<>());
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "tiktok-downloads-plus");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadLocal<Boolean> ORIGINAL = new ThreadLocal<>();
    private static volatile boolean videoAvailable;
    private static volatile boolean photosAvailable;
    private static volatile String status = "Not installed";

    private DownloadsPlus() { }

    /** Call once in TikTok's main process while {@link Discovery} is open, even when toggles are off. */
    static void install(ClassLoader loader) {
        if (loader == null || !INSTALLED.compareAndSet(false, true)) return;
        discover(loader);
    }

    public static boolean videoAvailable() { return videoAvailable; }
    public static boolean photosAvailable() { return photosAvailable; }
    public static String status() { return status; }

    private static void discover(ClassLoader loader) {
        String video = "missing model or unique download action";
        String photos = "missing model or unique photo action";
        try {
            if (Build.VERSION.SDK_INT < 29) {
                video = photos = "scoped storage requires Android 10";
            } else {
                Class<?> aweme = Hooks.findClass(AWEME, loader);
                Class<?> address = Hooks.findClass(URL_MODEL, loader);
                if (aweme != null && address != null && Reflect.method(address, "getUrlList") != null) {
                    Class<?> model = Hooks.findClass(VIDEO, loader);
                    if (model != null && Reflect.method(model, "getBitRate") != null
                            && Reflect.method(model, "getPlayAddr") != null) {
                        try {
                            videoAvailable = hookAction(loader, aweme, "download_method", "single_song", false);
                        } catch (Throwable ignored) { }
                        if (videoAvailable) video = "ready";
                    }
                    Class<?> info = Hooks.findClass("com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo", loader);
                    if (info != null && Reflect.method(info, "getImageList") != null) {
                        try {
                            photosAvailable = hookAction(loader, aweme, "download_popup_show",
                                    "single_image_mode_download", true);
                        } catch (Throwable ignored) { }
                        if (photosAvailable) photos = "ready";
                    }
                }
            }
        } catch (Throwable ignored) {
            if (!videoAvailable) video = "discovery unavailable";
            if (!photosAvailable) photos = "discovery unavailable";
        }
        status = "downloads: video " + video + ", photos " + photos;
        if (!videoAvailable || !photosAvailable) RuntimeLog.log(status);
    }

    private static boolean hookAction(ClassLoader loader, Class<?> aweme, String first, String second, boolean photo) {
        Set<String> other = new HashSet<>(Discovery.methodsUsing(second));
        Set<Method> matches = new HashSet<>();
        for (String entry : Discovery.methodsUsing(first)) {
            if (!other.contains(entry)) continue;
            int separator = entry.lastIndexOf('#');
            if (separator <= 0) continue;
            Class<?> owner = Hooks.findClass(entry.substring(0, separator), loader);
            if (owner == null || modelField(owner, aweme) == null || !hasActivity(owner)) continue;
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(entry.substring(separator + 1)) || method.getReturnType() != void.class
                        || Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] args = method.getParameterTypes();
                boolean context = false;
                boolean model = false;
                for (Class<?> arg : args) {
                    context |= Context.class.isAssignableFrom(arg);
                    model |= arg == aweme;
                }
                if (context && (photo ? args.length == 2 && !model : model)) matches.add(method);
            }
        }
        // A changed or ambiguous fingerprint must never intercept unrelated share actions.
        if (matches.size() != 1) return false;
        Method action = matches.iterator().next();
        Field model = modelField(action.getDeclaringClass(), aweme);
        try {
            Hooks.hook(action, new Hooks.Hook() {
                @Override protected void before(Hooks.Call call) {
                    if (!videoDownloads && !saveAllPhotos || ORIGINAL.get() != null) return;
                    try {
                        if (Looper.myLooper() != Looper.getMainLooper()) return;
                        Object post = null;
                        Context context = null;
                        for (Object arg : call.args) {
                            if (aweme.isInstance(arg)) post = arg;
                            if (arg instanceof Context) context = (Context) arg;
                        }
                        if (post == null) post = model.get(call.thisObject);
                        if (post == null) return;
                        Activity activity = Views.activity(context);
                        if (activity == null) activity = ownerActivity(call.thisObject);
                        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
                        handle(call, activity, post);
                    } catch (Throwable ignored) { }
                }
            });
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void handle(Hooks.Call call, Activity activity, Object post) {
        List<?> images = DownloadModels.photoImages(post);
        boolean photo = !images.isEmpty();
        if (photo ? !saveAllPhotos : !videoDownloads) return;
        String id = String.valueOf(Reflect.call(post, "getAid"));
        if (id.isEmpty() || "null".equals(id)) return;
        String key = id;
        if (ACTIVE.contains(key)) {
            call.setResult(null);
            return;
        }
        List<DownloadSheet.Row> rows = new ArrayList<>();
        List<List<DownloadModels.Item>> jobs = new ArrayList<>();
        if (!options(post, photo, rows, jobs)) return;
        // TikTok's own download stays one tap away: its photo picker, slideshow video and watermark.
        rows.add(new DownloadSheet.Row(I18n.get(photo ? I18n.S.DL_PICK_PHOTOS : I18n.S.DL_ORIGINAL),
                I18n.get(photo ? I18n.S.DL_PICK_PHOTOS_DESC : I18n.S.DL_ORIGINAL_DESC),
                Glyph.Kind.DOWNLOAD, DownloadSheet.APP_TILE, false));
        jobs.add(null);
        Method action = call.method instanceof Method ? (Method) call.method : null;
        Object owner = call.thisObject;
        Object[] args = call.args.clone();
        Object author = Reflect.call(post, "getAuthor");
        Object handle = author == null ? null : Reflect.call(author, "getUniqueId");
        AtomicBoolean submitted = new AtomicBoolean();
        ACTIVE.add(key);
        try {
            DownloadSheet.show(activity, I18n.get(photo ? I18n.S.DL_TITLE_PHOTOS : I18n.S.DL_TITLE),
                    handle == null ? null : "@" + handle, rows,
                    which -> {
                        if (which < 0 || which >= jobs.size()) return;
                        List<DownloadModels.Item> job = jobs.get(which);
                        if (job == null) {
                            original(action, owner, args);
                        } else {
                            submitted.set(download(activity, id, key, job));
                        }
                    },
                    () -> { if (!submitted.get()) ACTIVE.remove(key); });
            call.setResult(null);
        } catch (RuntimeException ignored) {
            ACTIVE.remove(key);
        }
    }

    /** The photos, or each video quality, then the sound, as sheet rows with their jobs. False without media. */
    private static boolean options(Object post, boolean photo, List<DownloadSheet.Row> rows,
            List<List<DownloadModels.Item>> jobs) {
        if (photo) {
            List<DownloadModels.Item> pictures = DownloadModels.photos(DownloadModels.photoImages(post));
            if (pictures.isEmpty()) return false;
            rows.add(new DownloadSheet.Row(I18n.get(pictures.size() > 1 ? I18n.S.DL_ALL_PHOTOS : I18n.S.DL_PHOTO),
                    pictures.size() > 1
                            ? String.format(Locale.ROOT, I18n.get(I18n.S.DL_PHOTO_COUNT), pictures.size())
                            : DownloadFormat.dimensions(pictures.get(0).width, pictures.get(0).height),
                    Glyph.Kind.IMAGE, DownloadSheet.PHOTO_TILE, false));
            jobs.add(pictures);
        } else {
            List<DownloadModels.Item> videos = DownloadModels.videos(post);
            if (videos.isEmpty()) return false;
            for (int i = 0; i < videos.size(); i++) {
                DownloadModels.Item video = videos.get(i);
                rows.add(new DownloadSheet.Row(DownloadFormat.resolution(video.width, video.height),
                        DownloadFormat.size(video.bytes, video.estimated), Glyph.Kind.PLAY,
                        DownloadSheet.VIDEO_TILE, i == 0 && videos.size() > 1));
                jobs.add(Collections.singletonList(video));
            }
        }
        DownloadModels.Item audio = DownloadModels.audio(post);
        if (audio != null) {
            rows.add(new DownloadSheet.Row(I18n.get(I18n.S.DL_AUDIO), DownloadFormat.size(audio.bytes, false),
                    Glyph.Kind.NOTE, DownloadSheet.AUDIO_TILE, false));
            jobs.add(Collections.singletonList(audio));
        }
        return true;
    }

    /**
     * The download sheet for a post that has no TikTok download action, such as a story. Returns
     * false when the post has nothing to save; {@code dismissed} runs when the sheet closes.
     */
    static boolean offer(Activity activity, Object post, Runnable dismissed) {
        String id = String.valueOf(Reflect.call(post, "getAid"));
        if (id.isEmpty() || "null".equals(id) || ACTIVE.contains(id)) return false;
        List<DownloadSheet.Row> rows = new ArrayList<>();
        List<List<DownloadModels.Item>> jobs = new ArrayList<>();
        boolean photo = !DownloadModels.photoImages(post).isEmpty();
        if (!options(post, photo, rows, jobs)) return false;
        Object author = Reflect.call(post, "getAuthor");
        Object handle = author == null ? null : Reflect.call(author, "getUniqueId");
        AtomicBoolean submitted = new AtomicBoolean();
        ACTIVE.add(id);
        try {
            DownloadSheet.show(activity, I18n.get(photo ? I18n.S.DL_TITLE_PHOTOS : I18n.S.DL_TITLE),
                    handle == null ? null : "@" + handle, rows,
                    which -> {
                        if (which >= 0 && which < jobs.size()) submitted.set(download(activity, id, id, jobs.get(which)));
                    },
                    () -> {
                        if (!submitted.get()) ACTIVE.remove(id);
                        if (dismissed != null) dismissed.run();
                    });
            return true;
        } catch (RuntimeException ignored) {
            ACTIVE.remove(id);
            return false;
        }
    }

    /** Runs TikTok's own download action, past this hook. */
    private static void original(Method action, Object owner, Object[] args) {
        if (action == null) return;
        ORIGINAL.set(true);
        try {
            action.setAccessible(true);
            action.invoke(owner, args);
        } catch (Throwable ignored) {
            // TikTok's action failed by itself; nothing was saved.
        } finally {
            ORIGINAL.remove();
        }
    }

    /** Saves media found elsewhere (comment stickers and photos) through the same queue. */
    static boolean save(Context context, String id, List<DownloadModels.Item> items) {
        return !items.isEmpty() && download(context, id, "media:" + id, items);
    }

    /** Returns false before consuming TikTok's action if the bounded job queue is full. */
    private static boolean download(Context context, String id, String key, List<DownloadModels.Item> choices) {
        Context app = context.getApplicationContext();
        if (app == null) return false;
        List<DownloadModels.Item> items = new ArrayList<>(choices);
        String batch = Long.toString(System.currentTimeMillis()) + '_' + UUID.randomUUID().toString().substring(0, 8);
        try {
            WORKER.execute(() -> {
                int saved = 0;
                try {
                    for (int i = 0; i < items.size(); i++) if (MediaDownload.save(app, items.get(i), id, batch, i + 1)) saved++;
                } catch (Throwable ignored) {
                } finally {
                    ACTIVE.remove(key);
                    String message;
                    if (saved == items.size()) {
                        message = I18n.get(I18n.S.DL_SAVED) + " " + MediaDownload.directory(items.get(0).kind) + "/TikTok";
                    } else if (saved == 0) {
                        message = I18n.get(I18n.S.DL_FAILED);
                    } else {
                        message = String.format(Locale.ROOT, I18n.get(I18n.S.DL_PARTIAL), saved, items.size());
                    }
                    toast(app, message);
                }
            });
            toast(app, I18n.get(I18n.S.DL_STARTED));
            return true;
        } catch (RuntimeException ignored) {
            toast(app, I18n.get(I18n.S.DL_BUSY));
            return false;
        }
    }

    private static void toast(Context app, String message) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try { Toast.makeText(app, message, Toast.LENGTH_SHORT).show(); } catch (RuntimeException ignored) { }
            });
        } catch (RuntimeException ignored) { }
    }

    private static Field modelField(Class<?> owner, Class<?> aweme) {
        Field found = null;
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != aweme) continue;
                if (found != null) return null;
                field.setAccessible(true);
                found = field;
            }
        }
        return found;
    }

    private static boolean hasActivity(Class<?> owner) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && Activity.class.isAssignableFrom(field.getType())) return true;
            }
        }
        return false;
    }

    private static Activity ownerActivity(Object owner) throws IllegalAccessException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !Activity.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Activity value = (Activity) field.get(owner);
                if (value != null) return value;
            }
        }
        return null;
    }
}
