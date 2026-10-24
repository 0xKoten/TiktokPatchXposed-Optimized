package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Uploads in HD, as if "Upload HD" were switched on for every post. TikTok's choice is read by
 * three static methods of one class, next to the remembered switch (USER_HD_VIDEO_SWITCH_SETTING):
 * the decision for the current upload speed, the same choice as text for TikTok's statistics, and
 * "is it switched on". All three answer "on"; TikTok still checks whether the video can be HD, picks
 * the size and bitrate, and its server may compress the video again. Install after Discovery.init.
 */
final class HdUpload {
    private static final String KEY = "USER_HD_VIDEO_SWITCH_SETTING";

    private HdUpload() { }

    static void install(ClassLoader loader) {
        // Only the methods that read the switch itself; their class has other () -> boolean settings.
        java.util.Map<String, Set<String>> owners = new java.util.HashMap<>();
        for (String hit : Discovery.methodsUsing(KEY)) {
            int split = hit.indexOf('#');
            if (split > 0) owners.computeIfAbsent(hit.substring(0, split), key -> new HashSet<>()).add(hit.substring(split + 1));
        }
        int hooks = 0;
        List<String> readers = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Set<String>> entry : owners.entrySet()) {
            String name = entry.getKey();
            Class<?> owner = Hooks.findClass(name, loader);
            if (owner == null) continue;
            List<Method> decisions = new java.util.ArrayList<>();
            List<Method> labels = new java.util.ArrayList<>();
            List<Method> switches = new java.util.ArrayList<>();
            for (Method method : owner.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (!Modifier.isStatic(modifiers) || !entry.getValue().contains(method.getName())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 1 && p[0] == long.class && method.getReturnType() == boolean.class) {
                    decisions.add(method);
                } else if (p.length == 1 && p[0] == long.class && method.getReturnType() == String.class) {
                    labels.add(method);
                } else if (p.length == 0 && method.getReturnType() == boolean.class) {
                    switches.add(method);
                }
            }
            // Exactly one of each, or nothing: a half-changed choice would disagree with itself.
            if (decisions.size() != 1 || labels.size() != 1 || switches.size() != 1) continue;
            Method decision = decisions.get(0);
            Method label = labels.get(0);
            Method switched = switches.get(0);
            hook(decision, true);
            readers.addAll(Discovery.methodsCalling(name, decision.getName()));
            hook(label, "1");
            hook(switched, true);
            hooks += 3;
        }
        RuntimeLog.log("hd upload: owners=" + owners.size() + " hooks=" + hooks + ", bitrate=" + installBitrate(loader, readers));
    }

    private static final String SETTINGS = "com.ss.android.vesdk.VEVideoEncodeSettings";
    /** Bitrate choices in Mbit/s; 0 keeps TikTok's own (about 4 Mbit/s for 1080p). Past 30 the server's re-encode keeps nothing more. */
    static final int[] CHOICES = {0, 12, 20, 30};

    /**
     * Higher bitrate: TikTok builds the encoder settings for a post in methods that read the HD
     * choice and return those settings; the bitrate floors go into what they return, whichever
     * editor then encodes with them.
     */
    private static int installBitrate(ClassLoader loader, List<String> readers) {
        Class<?> settings = Hooks.findClass(SETTINGS, loader);
        if (settings == null) return 0;
        Set<Class<?>> builders = new HashSet<>();
        for (String hit : readers) {
            int split = hit.indexOf('#');
            Class<?> owner = split > 0 ? Hooks.findClass(hit.substring(0, split), loader) : null;
            if (owner != null) builders.add(owner);
        }
        int hooks = 0;
        for (Class<?> builder : builders) {
            for (Method method : builder.getDeclaredMethods()) {
                if (method.getReturnType() != settings || Modifier.isAbstract(method.getModifiers())) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        long mbps = Prefs.number(Prefs.HD_BITRATE);
                        Object result = param.getResult();
                        if (!Prefs.on(Prefs.HD_UPLOAD) || mbps <= 0 || result == null) return;
                        boost(result, (int) mbps);
                    }
                });
                hooks++;
            }
        }
        return hooks;
    }

    /** Bitrate floors for both encoders; the software one gets a matching quality (lower CRF is better). */
    private static void boost(Object settings, int mbps) {
        int bps = mbps * 1_000_000;
        long max = bps + bps / 4L;
        int crf = mbps >= 30 ? 16 : mbps >= 20 ? 17 : 18;
        raise(settings, "bps", bps);
        raise(settings, "HwBps", bps);
        raise(settings, "swMaxrate", max);
        lower(settings, "swCRF", crf);
        // TikTok's JSON presets would put its own bitrate back.
        set(settings, "ignoreJsonBitrate", true);
        Object compile = Reflect.get(settings, "mVideoCompileEncodeSetting");
        raise(Reflect.get(compile, "mHWEncodeSetting"), "mBitrate", bps);
        Object software = Reflect.get(compile, "mSWEncodeSetting");
        raise(software, "mBps", bps);
        raise(software, "mMaxRate", max);
        lower(software, "mCrf", crf);
    }

    private static void raise(Object target, String name, long floor) {
        java.lang.reflect.Field field = target == null ? null : Reflect.field(target.getClass(), name);
        if (field == null) return;
        try {
            if (field.getType() == int.class && field.getInt(target) < floor) field.setInt(target, (int) floor);
            else if (field.getType() == long.class && field.getLong(target) < floor) field.setLong(target, floor);
        } catch (Throwable ignored) {
            // Left as TikTok set it.
        }
    }

    private static void lower(Object target, String name, int ceiling) {
        java.lang.reflect.Field field = target == null ? null : Reflect.field(target.getClass(), name);
        if (field == null || field.getType() != int.class) return;
        try {
            int value = field.getInt(target);
            if (value > ceiling) field.setInt(target, ceiling);
        } catch (Throwable ignored) {
            // Left as TikTok set it.
        }
    }

    private static void set(Object target, String name, boolean value) {
        java.lang.reflect.Field field = Reflect.field(target.getClass(), name);
        if (field == null || field.getType() != boolean.class) return;
        try {
            field.setBoolean(target, value);
        } catch (Throwable ignored) {
            // Left as TikTok set it.
        }
    }

    private static void hook(Method method, Object value) {
        Hooks.hook(method, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(Prefs.HD_UPLOAD)) param.setResult(value);
            }
        });
    }
}
