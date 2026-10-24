package com.golda.patchertiktok;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;

import java.lang.reflect.Method;

/**
 * Replaces TikTok's brand red with the user's color, and for the AMOLED theme the dark greys of
 * TikTok's dark theme with black. TikTok's color resources have obfuscated names, so colors are
 * matched by value as they are read (resources, theme attributes, color lists and parsed hex
 * strings); the alpha of each use is kept. Installed only when a switch is on at startup, so the
 * hooks cost nothing otherwise.
 */
final class AccentColor {
    /** TikTok's primary red, without alpha. */
    static final int BRAND = 0xFE2C55;
    /**
     * The red and its other shades, all replaced: lighter dark-theme ones (icons, a liked comment's
     * count), the orange-red of your own @name on the profile, and the big heart of a double-tap
     * like with the deeper red it starts from. TikTok's pinker reds stay: they color the streak flames.
     */
    private static final int[] BRANDS = {BRAND, 0xFF3B5C, 0xFF576F, 0xFF5B48, 0xFF3059, 0xF51230};
    /** TikTok's icons drawn from pictures: the follow "+" under the avatar is a red and white bitmap. */
    private static final String TUX_ICON = "com.bytedance.tux.drawable.TuxIconDrawable";

    /** Page backgrounds of TikTok's dark theme; they become pure black. */
    private static final int[] DARK = {0x121212, 0x161616, 0x161823, 0x181818};
    /**
     * TikTok's surface grey (sheets, chips, input fields) sits on those pages. It turns almost
     * black instead, so surfaces stay visible on the black page, as in other AMOLED themes.
     */
    private static final int SURFACE = 0x1E1E1E;
    private static final int AMOLED_SURFACE = 0x151515;

    private static volatile int accent;
    private static volatile boolean recolor;
    private static volatile boolean amoled;
    private static int hooks;

    private AccentColor() { }

    static void install() {
        accent = (int) Prefs.number(Prefs.ACCENT_COLOR) & 0xFFFFFF;
        recolor = Prefs.on(Prefs.ACCENT) && accent != BRAND;
        amoled = Prefs.on(Prefs.AMOLED);
        if (!recolor && !amoled) return;
        hookColors(Resources.class);
        hookColors(TypedArray.class);
        hooks += Hooks.hookAll(Color.class, "parseColor", COLOR);
        // Buttons such as Follow paint their shape and text with colors set in code, and vector
        // icons read theirs as a "complex color" while inflating.
        hooks += Hooks.hookAll(TypedArray.class, "getComplexColor", COMPLEX);
        // TikTok's design system reads its color tokens straight from the theme into a TypedValue.
        hooks += Hooks.hookAll(Resources.Theme.class, "resolveAttribute", THEME);
        hooks += Hooks.hookAll(android.graphics.drawable.GradientDrawable.class, "setColor", ARGUMENT);
        // Outlines keep their grey under AMOLED, so bubbles and fields stay visible on black.
        hooks += Hooks.hookAll(android.graphics.drawable.GradientDrawable.class, "setStroke", OUTLINE_ARGUMENT);
        hooks += Hooks.hookAll(android.graphics.drawable.GradientDrawable.class, "updateGradientDrawableStroke", OUTLINE);
        hooks += Hooks.hookAll(android.view.View.class, "setBackgroundColor", ARGUMENT);
        hooks += Hooks.hookAll(android.widget.TextView.class, "setTextColor", ARGUMENT);
        if (recolor) {
            // Animations (the like heart) build their colors from 0..1 channels with rounding, so
            // they arrive near the red rather than on it.
            hooks += Hooks.hookAll(Color.class, "argb", COLOR);
            hooks += Hooks.hookAll(Color.class, "rgb", COLOR);
            // Colored words inside text, such as names and mentions.
            hooks += Hooks.hookConstructors(android.text.style.ForegroundColorSpan.class, ARGUMENT);
            // Red pictures set straight on an image view, such as hearts.
            hooks += Hooks.hookAll(android.widget.ImageView.class, "setImageDrawable", PICTURE);
            // Icons are tinted with color filters made from a color, whichever code asks for the
            // tint (the liked heart is tinted from a state list at draw time).
            hooks += Hooks.hookConstructors(android.graphics.PorterDuffColorFilter.class, ARGUMENT);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                hooks += Hooks.hookConstructors(android.graphics.BlendModeColorFilter.class, ARGUMENT);
            }
            hooks += Hooks.hookConstructors(android.graphics.ColorMatrixColorFilter.class, MATRIX);
        }
        RuntimeLog.log("colors: hooks=" + hooks + " accent=" + recolor + " amoled=" + amoled);
    }

    /**
     * Icons painted from bitmaps keep their red pixels whatever the color hooks return, so those
     * with TikTok's red get a color matrix that turns the red into the accent and keeps white,
     * grey and black as they are.
     */
    static void installIcons(ClassLoader loader) {
        if (!recolor) return;
        Class<?> icon = Hooks.findClass(TUX_ICON, loader);
        if (icon == null) {
            RuntimeLog.log("accent color: picture icons not found");
            return;
        }
        // Every Tux icon is tinted through one static (Drawable, color) helper, including the color
        // its own state list picks at draw time, such as the liked heart's selected state.
        for (Method method : icon.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                    && p.length == 2 && p[0] == android.graphics.drawable.Drawable.class && p[1] == int.class) {
                Hooks.hook(method, ARGUMENT);
                hooks++;
            }
        }
        java.util.List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : icon.getDeclaredFields()) {
            if (field.getType() == android.graphics.drawable.Drawable.class) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        iconFields = fields;
        iconClass = icon;
        // Some icons get a ready-made red tint filter, made before the hooks or reused from a
        // cache, so it is swapped on the picture right before the icon draws.
        hooks += Hooks.hookAll(icon, "draw", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                for (java.lang.reflect.Field field : fields) {
                    try {
                        Object picture = field.get(param.thisObject);
                        if (picture instanceof android.graphics.drawable.Drawable) swapFilter((android.graphics.drawable.Drawable) picture);
                    } catch (IllegalAccessException ignored) {
                        // Left in TikTok's colors.
                    }
                }
            }
        });
        Hooks.hookConstructors(icon, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                for (java.lang.reflect.Field field : fields) {
                    try {
                        Object inner = field.get(param.thisObject);
                        if (inner instanceof android.graphics.drawable.BitmapDrawable) recolor((android.graphics.drawable.BitmapDrawable) inner);
                    } catch (IllegalAccessException ignored) {
                        // Left in TikTok's colors.
                    }
                }
            }
        });
    }

    /**
     * A "paint everything this color" matrix: no input colors, the color in the offsets. TikTok's
     * icons are tinted that way; such a red is swapped, any other matrix is left alone.
     */
    private static final Hooks.Hook MATRIX = new Hooks.Hook() {
        @Override protected void before(Hooks.Call param) {
            if (param.args.length != 1) return;
            float[] matrix = param.args[0] instanceof float[] ? (float[]) param.args[0]
                    : param.args[0] instanceof android.graphics.ColorMatrix ? ((android.graphics.ColorMatrix) param.args[0]).getArray() : null;
            if (matrix == null || matrix.length != 20) return;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 4; column++) if (matrix[row * 5 + column] != 0f) return;
            }
            int rgb = (Math.round(matrix[4]) << 16) | (Math.round(matrix[9]) << 8) | Math.round(matrix[14]);
            if (!isBrand(rgb)) return;
            float[] swapped = matrix.clone();
            swapped[4] = (accent >> 16) & 0xFF;
            swapped[9] = (accent >> 8) & 0xFF;
            swapped[14] = accent & 0xFF;
            param.args[0] = param.args[0] instanceof float[] ? swapped : new android.graphics.ColorMatrix(swapped);
        }
    };

    private static final Hooks.Hook PICTURE = new Hooks.Hook() {
        @Override protected void before(Hooks.Call param) {
            if (param.args.length > 0) recolorTree(param.args[0], 0);
        }
    };

    /**
     * Bitmaps anywhere in a drawable: a state list keeps the liked heart as a child that is not
     * shown yet when the drawable is set, and layers stack several pictures.
     */
    private static void recolorTree(Object drawable, int depth) {
        if (depth > 3 || drawable == null) return;
        if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
            recolor((android.graphics.drawable.BitmapDrawable) drawable);
        } else if (drawable instanceof android.graphics.drawable.DrawableContainer) {
            android.graphics.drawable.Drawable.ConstantState state = ((android.graphics.drawable.Drawable) drawable).getConstantState();
            if (state instanceof android.graphics.drawable.DrawableContainer.DrawableContainerState) {
                android.graphics.drawable.Drawable[] children =
                        ((android.graphics.drawable.DrawableContainer.DrawableContainerState) state).getChildren();
                if (children != null) for (android.graphics.drawable.Drawable child : children) recolorTree(child, depth + 1);
            }
        } else if (drawable instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) recolorTree(layers.getDrawable(i), depth + 1);
        } else if (iconClass != null && iconClass.isInstance(drawable)) {
            // A Tux icon wraps its picture, which is often set after the icon is made.
            for (java.lang.reflect.Field field : iconFields) {
                try {
                    recolorTree(field.get(drawable), depth + 1);
                } catch (IllegalAccessException ignored) {
                    // Left in TikTok's colors.
                }
            }
        }
    }

    /** A red picture inside an icon is drawn through the red-to-accent filter, whatever filter it had. */
    private static void swapFilter(android.graphics.drawable.Drawable picture) {
        if (!(picture instanceof android.graphics.drawable.BitmapDrawable)) return;
        android.graphics.Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) picture).getBitmap();
        if (bitmap == null) return;
        if (CHECKED.get(bitmap) == null) recolor((android.graphics.drawable.BitmapDrawable) picture);
        if (!Boolean.TRUE.equals(CHECKED.get(bitmap))) return;
        if (brandFilter == null) brandFilter = new android.graphics.ColorMatrixColorFilter(brandMatrix());
        if (picture.getColorFilter() != brandFilter) picture.setColorFilter(brandFilter);
    }

    private static volatile Class<?> iconClass;
    private static volatile java.util.List<java.lang.reflect.Field> iconFields = java.util.Collections.emptyList();

    /** Icon bitmaps are shared between drawables, so each one is checked once. */
    private static final java.util.Map<android.graphics.Bitmap, Boolean> CHECKED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static volatile android.graphics.ColorMatrixColorFilter brandFilter;

    private static void recolor(android.graphics.drawable.BitmapDrawable drawable) {
        android.graphics.Bitmap bitmap = drawable.getBitmap();
        if (bitmap == null) return;
        Boolean brand = CHECKED.get(bitmap);
        if (brand == null) {
            // Pictures kept on the GPU cannot be read; a small copy is checked once instead.
            android.graphics.Bitmap readable = bitmap;
            if (android.os.Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == android.graphics.Bitmap.Config.HARDWARE) {
                try {
                    readable = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                } catch (RuntimeException unreadable) {
                    readable = null;
                }
            }
            brand = readable != null && hasBrand(readable);
            if (readable != null && readable != bitmap) readable.recycle();
            CHECKED.put(bitmap, brand);
        }
        if (!brand) return;
        if (brandFilter == null) brandFilter = new android.graphics.ColorMatrixColorFilter(brandMatrix());
        drawable.setColorFilter(brandFilter);
    }

    private static boolean hasBrand(android.graphics.Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int step = Math.max(1, Math.max(width, height) / 48);
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                int pixel = bitmap.getPixel(x, y);
                if ((pixel >>> 24) == 0xFF && isBrandPicture(pixel & 0xFFFFFF)) return true;
            }
        }
        return false;
    }

    /**
     * Each output channel is a·R + b·(G + B)/2 with a + b = 1, so greys map to themselves, and a is
     * chosen so the brand red lands on the accent.
     */
    private static float[] brandMatrix() {
        float red = (BRAND >> 16) & 0xFF;
        float rest = (((BRAND >> 8) & 0xFF) + (BRAND & 0xFF)) / 2f;
        float[] matrix = new float[20];
        int[] target = {(accent >> 16) & 0xFF, (accent >> 8) & 0xFF, accent & 0xFF};
        for (int channel = 0; channel < 3; channel++) {
            float a = (target[channel] - rest) / (red - rest);
            float b = (1 - a) / 2f;
            matrix[channel * 5] = a;
            matrix[channel * 5 + 1] = b;
            matrix[channel * 5 + 2] = b;
        }
        matrix[18] = 1f;
        return matrix;
    }

    /** TikTok serves resources through its own Resources subclass, which overrides the getters. */
    static void installFor(Context context) {
        if ((!recolor && !amoled) || context == null) return;
        Class<?> type = context.getResources().getClass();
        while (type != null && type != Resources.class) {
            hookColors(type);
            type = type.getSuperclass();
        }
    }

    private static void hookColors(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || java.lang.reflect.Modifier.isAbstract(method.getModifiers())) continue;
            if (method.getName().equals("getColor") && method.getReturnType() == int.class) {
                Hooks.hook(method, COLOR);
                hooks++;
            } else if (method.getName().equals("getColorStateList") && method.getReturnType() == ColorStateList.class) {
                Hooks.hook(method, LIST);
                hooks++;
            }
        }
    }

    private static final Hooks.Hook COLOR = new Hooks.Hook() {
        @Override protected void after(Hooks.Call param) {
            Object value = param.getResult();
            if (value instanceof Integer) param.setResult(swap((Integer) value));
        }
    };

    private static final Hooks.Hook LIST = new Hooks.Hook() {
        @Override protected void after(Hooks.Call param) {
            Object value = param.getResult();
            if (!(value instanceof ColorStateList)) return;
            ColorStateList list = (ColorStateList) value;
            ColorStateList swapped = swap(list);
            if (swapped != list) param.setResult(swapped);
        }
    };

    /** Swaps the brand red in a color argument (an int or a color list) before the call uses it. */
    private static final Hooks.Hook ARGUMENT = new Hooks.Hook() {
        @Override protected void before(Hooks.Call param) {
            swapArguments(param);
        }
    };

    private static void swapArguments(Hooks.Call param) {
        Object[] args = param.args;
        // Constructors too: color filters and text spans take their color there.
        Class<?>[] types = param.method instanceof java.lang.reflect.Executable
                ? ((java.lang.reflect.Executable) param.method).getParameterTypes() : null;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer && types != null && i < types.length && types[i] == int.class) {
                int color = (Integer) args[i];
                // setStroke(width, color): only the color argument looks like an opaque red.
                if (swap(color) != color) args[i] = swap(color);
            } else if (args[i] instanceof ColorStateList) {
                args[i] = swap((ColorStateList) args[i]);
            }
        }
    }

    /** Depth of outline code on this thread: shape strokes are read and set while it is above zero. */
    private static final ThreadLocal<int[]> OUTLINES = ThreadLocal.withInitial(() -> new int[1]);

    private static final Hooks.Hook OUTLINE = new Hooks.Hook() {
        @Override protected void before(Hooks.Call param) {
            OUTLINES.get()[0]++;
        }

        @Override protected void after(Hooks.Call param) {
            OUTLINES.get()[0]--;
        }
    };

    private static final Hooks.Hook OUTLINE_ARGUMENT = new Hooks.Hook() {
        @Override protected void before(Hooks.Call param) {
            OUTLINES.get()[0]++;
            swapArguments(param);
        }

        @Override protected void after(Hooks.Call param) {
            OUTLINES.get()[0]--;
        }
    };

    private static boolean outline() {
        return OUTLINES.get()[0] > 0;
    }

    /**
     * The AMOLED theme needs TikTok's own dark theme underneath. TikTok keeps that choice in the
     * "sp_dark_mode" preferences (1 light, 2 dark, -1 like the system) and reads it as each screen
     * opens, so it is set to dark while the theme is on. The previous choice is kept and put back
     * when the theme is turned off.
     */
    static void applyDarkMode(Context context) {
        if (context == null) return;
        android.content.SharedPreferences modes = context.getSharedPreferences("sp_dark_mode", Context.MODE_PRIVATE);
        String saved = "tpx_saved_dark_mode";
        if (Prefs.on(Prefs.AMOLED)) {
            int current = modes.getInt("dark_mode", 1);
            if (current == 2) return;
            android.content.SharedPreferences.Editor editor = modes.edit().putInt("dark_mode", 2);
            if (!modes.contains(saved)) editor.putInt(saved, current);
            editor.commit();
        } else if (modes.contains(saved)) {
            modes.edit().putInt("dark_mode", modes.getInt(saved, 1)).remove(saved).commit();
        }
    }

    private static final Hooks.Hook THEME = new Hooks.Hook() {
        @Override protected void after(Hooks.Call param) {
            if (!Boolean.TRUE.equals(param.getResult()) || !(param.args[1] instanceof android.util.TypedValue)) return;
            android.util.TypedValue value = (android.util.TypedValue) param.args[1];
            if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                value.data = swap(value.data);
            }
        }
    };

    private static final Hooks.Hook COMPLEX = new Hooks.Hook() {
        @Override protected void after(Hooks.Call param) {
            Object value = param.getResult();
            if (!(value instanceof ColorStateList)) return;
            ColorStateList list = (ColorStateList) value;
            ColorStateList swapped = swap(list);
            if (swapped != list) param.setResult(swapped);
        }
    };

    /**
     * Swaps the red in every state of a color list, not only the default one: a liked comment's
     * count, for example, is red only in its selected state. The states are read back through the
     * list's public parcel form.
     */
    static ColorStateList swap(ColorStateList list) {
        if (!list.isStateful()) {
            int color = list.getDefaultColor();
            return swap(color) == color ? list : ColorStateList.valueOf(swap(color));
        }
        // Outline lists keep their greys, so they are not cached with the background ones.
        if (outline()) return rebuild(list);
        ColorStateList known = SWAPPED.get(list);
        if (known != null) return known == UNCHANGED ? list : known;
        ColorStateList swapped = rebuild(list);
        // A value pointing back at its key would keep it alive forever.
        SWAPPED.put(list, swapped == list ? UNCHANGED : swapped);
        return swapped;
    }

    private static final ColorStateList UNCHANGED = ColorStateList.valueOf(0);

    /** Lists are shared by many views (often from the resource cache), so each is rebuilt once. */
    private static final java.util.Map<ColorStateList, ColorStateList> SWAPPED =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static ColorStateList rebuild(ColorStateList list) {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            list.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            int count = parcel.readInt();
            int[][] states = new int[count][];
            for (int i = 0; i < count; i++) states[i] = parcel.createIntArray();
            int[] colors = parcel.createIntArray();
            if (colors == null || colors.length != count) return list;
            boolean changed = false;
            for (int i = 0; i < count; i++) {
                int swapped = swap(colors[i]);
                changed |= swapped != colors[i];
                colors[i] = swapped;
            }
            return changed ? new ColorStateList(states, colors) : list;
        } catch (RuntimeException unreadable) {
            return list;
        } finally {
            parcel.recycle();
        }
    }

    /**
     * Pictures count as red only with TikTok's main red in them. The pinker shades also fill
     * pictures that must keep their colors, such as the streak flame for 30 to 100 days.
     */
    private static boolean isBrandPicture(int rgb) {
        return Math.abs(((rgb >> 16) & 0xFF) - ((BRAND >> 16) & 0xFF)) <= 3
                && Math.abs(((rgb >> 8) & 0xFF) - ((BRAND >> 8) & 0xFF)) <= 3
                && Math.abs((rgb & 0xFF) - (BRAND & 0xFF)) <= 3;
    }

    /** Within a few steps of a brand shade on every channel: rounding in animations and pictures. */
    private static boolean isBrand(int rgb) {
        for (int brand : BRANDS) {
            if (Math.abs(((rgb >> 16) & 0xFF) - ((brand >> 16) & 0xFF)) <= 3
                    && Math.abs(((rgb >> 8) & 0xFF) - ((brand >> 8) & 0xFF)) <= 3
                    && Math.abs((rgb & 0xFF) - (brand & 0xFF)) <= 3) return true;
        }
        return false;
    }

    static int swap(int color) {
        int rgb = color & 0xFFFFFF;
        if (recolor && isBrand(rgb)) return (color & 0xFF000000) | accent;
        if (amoled && !outline()) {
            for (int dark : DARK) if (rgb == dark) return color & 0xFF000000;
            if (rgb == SURFACE) return (color & 0xFF000000) | AMOLED_SURFACE;
        }
        return color;
    }
}
