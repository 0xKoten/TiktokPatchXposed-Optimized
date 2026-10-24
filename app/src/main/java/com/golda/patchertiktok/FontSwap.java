package com.golda.patchertiktok;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces TikTok's interface font. TikTok makes every text face in its own font engine (one
 * method by asset name, one by variable-font axes); the face each returns is swapped for the
 * same weight and slant of the chosen font. Icons and creative fonts use other paths and stay.
 * Applies when TikTok starts. Install after Discovery.init.
 */
final class FontSwap {
    /** Only TikTok's font engines load this file by name together with the two methods below. */
    private static final String ENGINE_FINGERPRINT = "font/TikTokSans-VF.otf";
    private static final String AXIS = "android.graphics.fonts.FontVariationAxis";
    static final String SOURCE_SYSTEM = "system";
    static final String SOURCE_FILE = "file";
    private static final long MAX_SIZE = 20L * 1024 * 1024;

    private static volatile Typeface base;
    private static final Map<Integer, Typeface> DERIVED = new ConcurrentHashMap<>();

    private FontSwap() { }

    static File file(Context context) {
        return new File(new File(context.getFilesDir(), "tpx_font"), "font");
    }

    static void install(Context context, ClassLoader loader) {
        if (!Prefs.on(Prefs.FONT) || Build.VERSION.SDK_INT < 26) return;
        base = load(context);
        if (base == null) {
            RuntimeLog.log("font: nothing to load");
            return;
        }
        int engines = 0;
        for (String name : Discovery.classesUsing(ENGINE_FINGERPRINT)) {
            Class<?> type = Hooks.findClass(name, loader);
            if (type == null) continue;
            // The engine may have private helpers of the same shape; take the methods of its interface.
            Method byName = null;
            Method byAxes = null;
            for (Class<?> contract : type.getInterfaces()) {
                for (Method declared : contract.getDeclaredMethods()) {
                    if (declared.getReturnType() != Typeface.class) continue;
                    Class<?>[] p = declared.getParameterTypes();
                    Method own;
                    try {
                        own = type.getDeclaredMethod(declared.getName(), p);
                    } catch (NoSuchMethodException missing) {
                        continue;
                    }
                    if (Modifier.isAbstract(own.getModifiers())) continue;
                    if (p.length == 1 && p[0] == String.class) byName = own;
                    else if (p.length == 3 && p[0].isArray() && AXIS.equals(p[0].getComponentType().getName())
                            && p[1] == int.class && p[2] == boolean.class) byAxes = own;
                }
            }
            if (byName == null || byAxes == null) continue;
            Hooks.Hook swap = new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    Object face = param.getResult();
                    if (!(face instanceof Typeface) || param.hasThrowable()) return;
                    Typeface original = (Typeface) face;
                    int weight = requestedWeight(param.args);
                    if (weight <= 0) weight = Build.VERSION.SDK_INT >= 28 ? original.getWeight() : (original.isBold() ? 700 : 400);
                    boolean italic = param.args.length == 3 && param.args[2] instanceof Boolean
                            ? (Boolean) param.args[2] : original.isItalic();
                    param.setResult(derive(original, weight, italic));
                }
            };
            try {
                Hooks.hook(byName, swap);
                Hooks.hook(byAxes, swap);
                engines++;
            } catch (Throwable ignored) { }
        }
        RuntimeLog.log("font: " + Prefs.text(Prefs.FONT_SOURCE) + ", engines " + engines);
    }

    private static Typeface load(Context context) {
        if (SOURCE_FILE.equals(Prefs.text(Prefs.FONT_SOURCE))) {
            File font = file(context);
            if (!font.isFile() || !looksLikeFont(font)) return null;
            try {
                return Typeface.createFromFile(font);
            } catch (RuntimeException broken) {
                return null;
            }
        }
        return Typeface.create("sans-serif", Typeface.NORMAL);
    }

    /**
     * TikTok's font is variable: the weight it wants is the "wght" axis (or the weight argument),
     * or a word in the asset name; the returned face itself often reports plain 400.
     */
    private static int requestedWeight(Object[] args) {
        if (args.length == 3 && args[0] instanceof Object[]) {
            for (Object axis : (Object[]) args[0]) {
                if (axis instanceof android.graphics.fonts.FontVariationAxis
                        && "wght".equals(((android.graphics.fonts.FontVariationAxis) axis).getTag())) {
                    return Math.round(((android.graphics.fonts.FontVariationAxis) axis).getStyleValue());
                }
            }
            if (args[1] instanceof Integer && (Integer) args[1] >= 100 && (Integer) args[1] <= 1000) return (Integer) args[1];
        } else if (args.length == 1 && args[0] instanceof String) {
            String name = ((String) args[0]).toLowerCase(java.util.Locale.ROOT);
            if (name.contains("black") || name.contains("heavy")) return 900;
            if (name.contains("extrabold")) return 800;
            if (name.contains("semibold")) return 600;
            if (name.contains("bold")) return 700;
            if (name.contains("medium")) return 500;
            if (name.contains("light")) return 300;
            if (name.contains("regular")) return 400;
        }
        return 0;
    }

    /** The chosen font in the weight and slant TikTok asked for. */
    private static Typeface derive(Typeface original, int weight, boolean italic) {
        Typeface chosen = base;
        if (chosen == null) return original;
        weight = Math.max(1, Math.min(1000, weight));
        Integer key = weight * 2 + (italic ? 1 : 0);
        Typeface cached = DERIVED.get(key);
        if (cached != null) return cached;
        Typeface made;
        try {
            made = Build.VERSION.SDK_INT >= 28 ? Typeface.create(chosen, weight, italic)
                    : Typeface.create(chosen, (weight >= 600 ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0));
        } catch (RuntimeException error) {
            return original;
        }
        DERIVED.put(key, made);
        return made;
    }

    /**
     * Copies a picked font into TikTok's private files, so the original can be deleted. Returns
     * null when it worked, or a short reason.
     */
    static String importFont(Context context, Uri uri) {
        File target = file(context);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return "storage";
        File temp = new File(dir, "font.tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(temp)) {
            if (in == null) return "read";
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            for (int read; (read = in.read(buffer)) > 0; ) {
                total += read;
                if (total > MAX_SIZE) return "size";
                out.write(buffer, 0, read);
            }
        } catch (IOException | SecurityException error) {
            temp.delete();
            return "read";
        }
        if (!looksLikeFont(temp)) {
            temp.delete();
            return "format";
        }
        try {
            Typeface.createFromFile(temp);
        } catch (RuntimeException broken) {
            temp.delete();
            return "format";
        }
        if (!temp.renameTo(target)) {
            temp.delete();
            return "storage";
        }
        return null;
    }

    /** TrueType, OpenType or a font collection, by the first four bytes. */
    private static boolean looksLikeFont(File file) {
        byte[] head = new byte[4];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(head) != 4) return false;
        } catch (IOException error) {
            return false;
        }
        int tag = ((head[0] & 0xFF) << 24) | ((head[1] & 0xFF) << 16) | ((head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        return tag == 0x00010000 || tag == 0x4F54544F /* OTTO */ || tag == 0x74727565 /* true */
                || tag == 0x74746366 /* ttcf */;
    }
}
