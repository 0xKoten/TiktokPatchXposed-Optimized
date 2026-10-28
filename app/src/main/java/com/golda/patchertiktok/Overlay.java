package com.golda.patchertiktok;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The layer TikTok draws over each video (buttons, author, caption) is one view,
 * InteractAreaRootLayout. Its own setAlpha is scaled by the user's opacity, so TikTok's fades keep
 * working. Burn-in protection for OLED screens dims that layer after a few seconds without touches
 * (any touch brings it back) and shifts it by a few pixels every minute. Parts of the layer can
 * also be hidden.
 */
final class Overlay {
    private static final String LAYER = "com.ss.android.ugc.feed.platform.componentmanager.view.InteractAreaRootLayout";
    private static final String FEED = "com.ss.android.ugc.aweme.feed.";
    /**
     * TikTok's own components for the parts over a video, by the switch that hides them. Their
     * class names are readable, so each switch hides exactly its parts.
     */
    private static final String[][] COMPONENTS = {
            {FEED + "assem.avatar.FeedAvatarAssemWrap", "buttons"},
            {FEED + "assem.digg.VideoDiggAssem", "buttons"},
            {FEED + "assem.videocomment.VideoCommentAssem", "buttons"},
            {FEED + "favorite.VideoFavoriteAssem", "buttons"},
            {FEED + "assem.share.VideoShareAssem", "buttons"},
            {FEED + "assem.music.VideoMusicCoverAssem", "music"},
            {FEED + "assem.videoauthorinfo.VideoAuthorInfoRelationAssem", "caption"},
            {FEED + "assem.desc.VideoDescAssem", "caption"},
            {FEED + "assem.music.VideoMusicTitleAssem", "caption"},
            {FEED + "assem.videoauthorinfo.VideoInfoGenreTagContainerAssem", "caption"},
            // The round TikTok Tako (AI assistant) button, and its variant above the column.
            {FEED + "assem.tikbot.TakoAssem", "tako"},
            {FEED + "assem.tikbot.TakoAssemRoof", "tako"},
    };
    /** Views of those components, by switch; hidden ones are also in {@link #OURS}. */
    private static final java.util.Map<View, String> PARTS = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.Map<View, Boolean> OURS = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** False when TikTok renamed the components: parts are then found by where they sit. */
    private static boolean components;
    private static final String REQUESTED = "com.golda.patchertiktok.alpha";
    private static final long IDLE_MS = 4_000L;
    private static final long SHIFT_MS = 60_000L;
    private static final float DIMMED = 0.3f;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<View>> LAYERS = new ArrayList<>();

    private static volatile long lastTouch = SystemClock.uptimeMillis();
    private static volatile boolean dimmed;
    private static int shift;

    private Overlay() { }

    static void install(ClassLoader loader) {
        Class<?> layer = Hooks.findClass(LAYER, loader);
        Method setAlpha = declared(layer, "setAlpha", float.class);
        Method touch = declared(layer, "dispatchTouchEvent", android.view.MotionEvent.class);
        if (setAlpha == null || touch == null) {
            RuntimeLog.log("overlay unavailable: interface layer not found");
            return;
        }
        Hooks.hook(setAlpha, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                float requested = (Float) param.args[0];
                Hooks.setExtra(param.thisObject, REQUESTED, requested);
                param.args[0] = requested * factor();
            }
        });
        Hooks.hook(touch, new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                lastTouch = SystemClock.uptimeMillis();
                if (dimmed) {
                    dimmed = false;
                    refresh();
                }
            }
        });
        try {
            Hooks.findAndHook(View.class, "setVisibility", int.class, new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    try {
                        View v = (View) param.thisObject;
                        if (v == null) return;
                        String kind = PARTS.get(v);
                        if (kind == null) kind = HIDDEN.get(v);
                        if (kind != null && partWanted(kind)) {
                            param.args[0] = "tako".equals(kind) ? View.GONE : View.INVISIBLE;
                        }
                    } catch (Throwable ignored) { }
                }
            });
            Hooks.findAndHook(View.class, "setAlpha", float.class, new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    try {
                        View v = (View) param.thisObject;
                        if (v == null) return;
                        String kind = PARTS.get(v);
                        if (kind == null) kind = HIDDEN.get(v);
                        if (kind != null && partWanted(kind)) {
                            param.args[0] = 0f;
                        }
                    } catch (Throwable ignored) { }
                }
            });
        } catch (Throwable t) {
            RuntimeLog.log("overlay part visibility hook error: " + t);
        }
        for (Constructor<?> constructor : layer.getDeclaredConstructors()) {
            Hooks.hook(constructor, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    try {
                        View view = (View) param.thisObject;
                        if (view == null) return;
                        synchronized (LAYERS) {
                            LAYERS.removeIf(ref -> ref.get() == null);
                            LAYERS.add(new WeakReference<>(view));
                        }
                        view.post(() -> apply(view));

                        // Trigger position-based hide on layout change only (not every frame)
                        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                            try {
                                hideParts(v);
                            } catch (Throwable ignored) { }
                        });

                        // Managed OnPreDrawListener that cleans up on detach
                        ViewTreeObserver.OnPreDrawListener preDrawListener = new ViewTreeObserver.OnPreDrawListener() {
                            @Override public boolean onPreDraw() {
                                try {
                                    if (!view.isAttachedToWindow() || view.getVisibility() != View.VISIBLE) {
                                        return true;
                                    }
                                    float expected = factor();
                                    if (Math.abs(view.getAlpha() - expected) > 0.02f) {
                                        view.animate().cancel();
                                        view.setAlpha(expected);
                                    }
                                    if (Prefs.on(Prefs.HIDE_BUTTONS) || Prefs.on(Prefs.HIDE_CAPTION)
                                            || Prefs.on(Prefs.HIDE_MUSIC) || Prefs.on(Prefs.HIDE_TAKO)) {
                                        synchronized (PARTS) {
                                            for (View part : PARTS.keySet()) {
                                                if (part != null && part.isAttachedToWindow()) enforce(part);
                                            }
                                        }
                                        synchronized (HIDDEN) {
                                            for (View part : HIDDEN.keySet()) {
                                                if (part != null && part.isAttachedToWindow()) enforce(part);
                                            }
                                        }
                                    }
                                } catch (Throwable ignored) { }
                                return true;
                            }
                        };

                        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                            @Override public void onViewAttachedToWindow(View v) {
                                try {
                                    ViewTreeObserver vto = v.getViewTreeObserver();
                                    if (vto != null && vto.isAlive()) {
                                        vto.removeOnPreDrawListener(preDrawListener);
                                        vto.addOnPreDrawListener(preDrawListener);
                                    }
                                    hideParts(v);
                                } catch (Throwable ignored) { }
                            }

                            @Override public void onViewDetachedFromWindow(View v) {
                                try {
                                    ViewTreeObserver vto = v.getViewTreeObserver();
                                    if (vto != null && vto.isAlive()) {
                                        vto.removeOnPreDrawListener(preDrawListener);
                                    }
                                } catch (Throwable ignored) { }
                            }
                        });

                        if (view.isAttachedToWindow()) {
                            ViewTreeObserver vto = view.getViewTreeObserver();
                            if (vto != null && vto.isAlive()) {
                                vto.addOnPreDrawListener(preDrawListener);
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            });
        }
        installParts(loader);
        schedule();
        Prefs.listen(() -> MAIN.post(() -> {
            refresh();
            schedule();
        }));
        RuntimeLog.log("overlay installed");
    }

    /**
     * Each component reports the view it made; the view is kept hidden while its switch is on,
     * also when TikTok shows it again for the next video, and shown again when it is turned off.
     */
    private static void installParts(ClassLoader loader) {
        java.util.Map<Class<?>, String> kinds = new java.util.LinkedHashMap<>();
        java.util.Set<Method> methods = new java.util.HashSet<>();
        boolean sides = false;
        for (String[] entry : COMPONENTS) {
            Class<?> type = Hooks.findClass(entry[0], loader);
            if (type == null) continue;
            try {
                // Usually inherited from TikTok's component base class, so the owner is checked below.
                methods.add(type.getMethod("onViewCreated", View.class));
                kinds.put(type, entry[1]);
                sides |= !"tako".equals(entry[1]);
            } catch (NoSuchMethodException ignored) {
                // Not a component in this version.
            }
        }
        components = sides;
        if (!kinds.containsValue("tako")) RuntimeLog.log("tako button not found");
        if (!sides) RuntimeLog.log("overlay parts: components not found, hiding by position");
        if (methods.isEmpty()) return;
        Hooks.Hook hook = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                try {
                    if (!(param.args[0] instanceof View)) return;
                    String kind = null;
                    for (java.util.Map.Entry<Class<?>, String> entry : kinds.entrySet()) {
                        if (entry.getKey().isInstance(param.thisObject)) {
                            kind = entry.getValue();
                            break;
                        }
                    }
                    if (kind == null) return;
                    View view = (View) param.args[0];
                    if (PARTS.put(view, kind) == null) {
                        view.getViewTreeObserver().addOnPreDrawListener(() -> {
                            try {
                                return !enforce(view);
                            } catch (Throwable t) {
                                return true;
                            }
                        });
                    }
                    enforce(view);
                } catch (Throwable ignored) { }
            }
        };
        for (Method method : methods) Hooks.hook(method, hook);
    }

    private static boolean partWanted(String kind) {
        switch (kind) {
            case "buttons": return Prefs.on(Prefs.HIDE_BUTTONS);
            case "music": return Prefs.on(Prefs.HIDE_MUSIC);
            case "caption": return Prefs.on(Prefs.HIDE_CAPTION);
            default: return Prefs.on(Prefs.HIDE_TAKO);
        }
    }

    /** Applies the switch to one part; true when it was just hidden, so that frame is skipped. */
    private static boolean enforce(View view) {
        if (view == null) return false;
        try {
            String kind = PARTS.get(view);
            if (kind == null) kind = HIDDEN.get(view);
            if (kind == null) return false;
            boolean ours = OURS.containsKey(view);
            if (partWanted(kind)) {
                int hidden = "tako".equals(kind) ? View.GONE : View.INVISIBLE;
                if (view.getVisibility() != hidden || view.getAlpha() > 0.01f) {
                    OURS.put(view, Boolean.TRUE);
                    view.setVisibility(hidden);
                    view.setAlpha(0f);
                    return true;
                }
                return false;
            }
            if (ours) {
                OURS.remove(view);
                int hidden = "tako".equals(kind) ? View.GONE : View.INVISIBLE;
                if (view.getVisibility() == hidden) {
                    view.setVisibility(View.VISIBLE);
                    view.setAlpha(1f);
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static Method declared(Class<?> type, String name, Class<?>... parameters) {
        if (type == null) return null;
        try {
            return type.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    /** Settings are read after the hooks go in; the watcher starts once they are known. */
    static void settingsLoaded() {
        MAIN.post(Overlay::schedule);
    }

    /** The burn-in watcher runs only while the protection is on. */
    private static void schedule() {
        MAIN.removeCallbacks(WATCH);
        if (Prefs.on(Prefs.BURN_IN)) MAIN.postDelayed(WATCH, 1_000L);
    }

    private static float factor() {
        float opacity = Math.max(0.1f, Math.min(1f, Prefs.number(Prefs.OVERLAY_OPACITY) / 100f));
        if (Prefs.number(Prefs.OVERLAY_OPACITY) <= 0) opacity = 1f;
        return dimmed && dims() ? Math.min(opacity, DIMMED) : opacity;
    }

    private static boolean dims() {
        return Prefs.on(Prefs.BURN_IN) && Prefs.on(Prefs.BURN_IN_DIM);
    }

    private static final String BUTTONS = "buttons";
    private static final String MUSIC = "music";
    private static final String CAPTION = "caption";
    /**
     * Every part hidden here, with the switch that hid it. They are brought back from this list,
     * not by walking the layer again: the part may sit in a video that is off screen, inside a
     * container TikTok has hidden for a moment, or somewhere else after TikTok moved it.
     */
    private static final java.util.Map<View, String> HIDDEN = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * Hides parts of the layer by where they sit, as TikTok's view ids are obfuscated: the narrow
     * column of buttons at the right edge, the author and caption block at the bottom left, and
     * the square music disc in the bottom right corner. Only views hidden here are shown again.
     */
    static void hideParts(View layer) {
        try {
            boolean buttons = Prefs.on(Prefs.HIDE_BUTTONS);
            boolean caption = Prefs.on(Prefs.HIDE_CAPTION);
            boolean music = Prefs.on(Prefs.HIDE_MUSIC);
            restore(buttons, caption, music);
            if (!buttons && !caption && !music) return;
            int width = layer.getWidth();
            int height = layer.getHeight();
            if (width <= 0 || height <= 0 || !(layer instanceof android.view.ViewGroup)) return;
            int[] origin = new int[2];
            layer.getLocationInWindow(origin);
            walk((android.view.ViewGroup) layer, origin, width, height, buttons, caption, music);
        } catch (Throwable ignored) { }
    }

    /** Shows again every part whose switch is now off. */
    private static void restore(boolean buttons, boolean caption, boolean music) {
        synchronized (HIDDEN) {
            if (HIDDEN.isEmpty()) return;
            java.util.Iterator<java.util.Map.Entry<View, String>> entries = HIDDEN.entrySet().iterator();
            while (entries.hasNext()) {
                java.util.Map.Entry<View, String> entry = entries.next();
                if (wanted(entry.getValue(), buttons, caption, music)) continue;
                View view = entry.getKey();
                entries.remove();
                // Only our own INVISIBLE is undone; TikTok may have hidden it since for its own reasons.
                if (view != null && view.getVisibility() == View.INVISIBLE) view.setVisibility(View.VISIBLE);
            }
        }
    }

    private static void walk(android.view.ViewGroup group, int[] origin, int width, int height,
                             boolean buttons, boolean caption, boolean music) {
        walkInternal(group, origin, width, height, buttons, caption, music, 0);
    }

    private static void walkInternal(android.view.ViewGroup group, int[] origin, int width, int height,
                                    boolean buttons, boolean caption, boolean music, int depth) {
        if (group == null || depth > 15) return;
        try {
            int[] at = new int[2];
            for (int index = 0; index < group.getChildCount(); index++) {
                View child = group.getChildAt(index);
                if (child == null) continue;
                String mark = HIDDEN.get(child);
                if (mark != null) {
                    // Still wanted hidden (restore ran first); TikTok may have shown it again.
                    if (child.getVisibility() == View.VISIBLE) child.setVisibility(View.INVISIBLE);
                    continue;
                }
                if (child.getVisibility() != View.VISIBLE) continue;
                child.getLocationInWindow(at);
                float left = (at[0] - origin[0]) / (float) width;
                float top = (at[1] - origin[1]) / (float) height;
                float right = left + child.getWidth() / (float) width;
                float w = right - left;
                String kind = null;
                if (left >= 0.78f && right >= 0.95f && w <= 0.25f) kind = BUTTONS;
                else if (left >= 0.6f && right >= 0.95f && top >= 0.75f && w <= 0.32f
                        && Math.abs(child.getWidth() - child.getHeight()) < child.getWidth() / 4) kind = MUSIC;
                else if (left <= 0.1f && right <= 0.85f && w >= 0.4f && top >= 0.6f) kind = CAPTION;
                if (kind != null) {
                    if (wanted(kind, buttons, caption, music)) {
                        HIDDEN.put(child, kind);
                        child.setVisibility(View.INVISIBLE);
                    }
                } else if (child instanceof android.view.ViewGroup) {
                    walkInternal((android.view.ViewGroup) child, origin, width, height, buttons, caption, music, depth + 1);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static boolean wanted(String kind, boolean buttons, boolean caption, boolean music) {
        return BUTTONS.equals(kind) ? buttons : MUSIC.equals(kind) ? music : caption;
    }

    /** Re-applies TikTok's own requested alpha through the scaled setter. */
    private static void apply(View view) {
        try {
            Object requested = Hooks.getExtra(view, REQUESTED);
            float alpha = requested instanceof Float ? (Float) requested : 1f;
            view.animate().cancel();
            view.setAlpha(alpha);
            if (!Prefs.on(Prefs.BURN_IN)) {
                view.setTranslationX(0);
                view.setTranslationY(0);
            }
        } catch (Throwable ignored) { }
    }

    private static void refresh() {
        try {
            restore(Prefs.on(Prefs.HIDE_BUTTONS), Prefs.on(Prefs.HIDE_CAPTION), Prefs.on(Prefs.HIDE_MUSIC));
            for (View view : layers()) {
                apply(view);
                hideParts(view);
            }
            synchronized (PARTS) {
                for (View part : new ArrayList<>(PARTS.keySet())) {
                    if (part != null) enforce(part);
                }
            }
        } catch (Throwable ignored) { }
    }

    private static List<View> layers() {
        List<View> views = new ArrayList<>();
        synchronized (LAYERS) {
            for (WeakReference<View> ref : LAYERS) {
                View view = ref.get();
                if (view != null && view.isAttachedToWindow()) views.add(view);
            }
        }
        return views;
    }

    /** Burn-in protection: dim after inactivity, shift slightly every minute. */
    private static final Runnable WATCH = new Runnable() {
        private long shifted = SystemClock.uptimeMillis();

        @Override public void run() {
            try {
                if (!Prefs.on(Prefs.BURN_IN)) return;
                long now = SystemClock.uptimeMillis();
                if (!dimmed && dims() && now - lastTouch > IDLE_MS) {
                    dimmed = true;
                    for (View view : layers()) {
                        Object requested = Hooks.getExtra(view, REQUESTED);
                        float alpha = requested instanceof Float ? (Float) requested : 1f;
                        // The animator bypasses setAlpha, so the target is scaled here.
                        view.animate().alpha(alpha * factor()).setDuration(600).start();
                    }
                }
                if (now - shifted > SHIFT_MS) {
                    shifted = now;
                    shift = (shift + 1) % 4;
                    float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
                    float dx = (shift == 1 ? 2 : shift == 3 ? -2 : 0) * density;
                    float dy = (shift == 0 ? 2 : shift == 2 ? -2 : 0) * density;
                    for (View view : layers()) view.animate().translationX(dx).translationY(dy).setDuration(1_500).start();
                }
                MAIN.postDelayed(this, 1_000L);
            } catch (Throwable ignored) { }
        }
    };
}
