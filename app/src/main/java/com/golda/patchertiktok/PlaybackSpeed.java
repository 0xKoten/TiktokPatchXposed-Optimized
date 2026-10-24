package com.golda.patchertiktok;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps explicit speed choices across feed items and supplies the native menu's speed list.
 * Install after Discovery.init, even with both settings off; callbacks read the volatile settings.
 * Method lookup and indexing run at installation. Missing anchors fail open.
 */
final class PlaybackSpeed {
    static volatile boolean remember = false;
    static volatile String speeds = "";

    private static final String AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme";
    private static final String FILE = "tiktokpatchxposed";
    private static final String KEY = "playback_speed";
    private static final String THREE_TIMES = "feed_support_3x_speed";
    private static final String INLINE_SPEEDS = "com.golda.patchertiktok.inlineSpeeds";
    private static final List<Float> DEFAULT_SPEEDS = Collections.unmodifiableList(
            Arrays.asList(0.5f, 1f, 1.5f, 2f));
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Integer> SELECTING = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> CHECKING = ThreadLocal.withInitial(() -> false);
    private static volatile SharedPreferences preferences;
    private static volatile float chosenSpeed = Float.NaN;
    private static volatile ParsedSpeeds parsed = new ParsedSpeeds("", DEFAULT_SPEEDS);
    private static boolean installed;

    private PlaybackSpeed() { }

    static synchronized void configure(boolean keepSpeed, String customSpeeds) {
        speeds = customSpeeds == null ? "" : customSpeeds;
        remember = keepSpeed;
        // Also accepts a choice written by the settings screen directly to the shared file.
        SharedPreferences prefs = preferences(null);
        if (prefs != null) readSpeed(prefs);
    }

    static float getSpeed() {
        preferences(null);
        float value = chosenSpeed;
        return Float.isFinite(value) ? value : 1f;
    }

    /** Sorted, distinct finite values in [0.25, 3]; unusable input returns the standard four speeds. */
    static List<Float> parseSpeeds(String input) {
        TreeSet<Float> values = new TreeSet<>();
        if (input != null) {
            for (String token : input.trim().split("[,;\\s]+")) {
                if (token.isEmpty()) continue;
                try {
                    float value = Float.parseFloat(token);
                    if (Float.isFinite(value)) values.add(clamp(value));
                } catch (NumberFormatException ignored) { }
            }
        }
        return new ArrayList<>(values.isEmpty() ? DEFAULT_SPEEDS : values);
    }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        preferences(null);
        try {
            installRemember(loader);
        } catch (Throwable error) {
            logOnce("remember-install", "remember hooks unavailable: " + error.getClass().getSimpleName());
        }
        try {
            installMenu(loader);
        } catch (Throwable error) {
            logOnce("menu-install", "custom menu unavailable: " + error.getClass().getSimpleName());
        }
    }

    private static void installRemember(ClassLoader loader) throws Throwable {
        Class<?> aweme = Hooks.findClass(AWEME, loader);
        if (aweme == null) {
            logOnce("remember-install", "remember hooks unavailable: Aweme missing");
            return;
        }
        // All three source literals occur in the same static selection method in 47.1.4.
        Set<String> selections = new LinkedHashSet<>(Discovery.methodsUsing("swipe_up_lock_persist"));
        selections.retainAll(Discovery.methodsUsing("long_press"));
        selections.retainAll(Discovery.methodsUsing("click_share_button"));
        Set<Method> candidates = matching(loader, selections, true, void.class,
                float.class, aweme, String.class, String.class);
        if (candidates.size() != 1) {
            logOnce("remember-install", "remember hooks unavailable: selection anchor missing or ambiguous");
            return;
        }
        Method select = candidates.iterator().next();
        Class<?> manager = select.getDeclaringClass();
        Method gate = null;
        Method restore = null;
        List<Method> getters = new ArrayList<>();
        for (Method method : manager.getDeclaredMethods()) {
            if (signature(method, true, boolean.class, aweme)
                    && calls(method).contains(AWEME + "#isCanPlay")) {
                if (gate != null) {
                    logOnce("remember-install", "remember hooks unavailable: ambiguous eligibility gate");
                    return;
                }
                gate = method;
            }
            if (signature(method, true, void.class, aweme, String.class)
                    && calls(method).contains(manager.getName() + "#" + select.getName())) {
                if (restore != null) {
                    logOnce("remember-install", "remember hooks unavailable: ambiguous restore");
                    return;
                }
                restore = method;
            }
            if (signature(method, true, float.class, aweme)
                    && calls(method).contains("android.text.TextUtils#equals")) getters.add(method);
        }
        if (gate == null || restore == null || getters.isEmpty()
                || !calls(restore).contains(manager.getName() + "#" + gate.getName())) {
            logOnce("remember-install", "remember hooks unavailable: restore, gate or speed getters missing");
            return;
        }
        select.setAccessible(true);
        gate.setAccessible(true);
        SpeedManager state = new SpeedManager(select, gate);
        // Suppress getter overrides during selection, including an explicit return to 1x.
        Hooks.hook(select, new Hooks.Hook() {
            @Override protected void before(Hooks.Call call) {
                SELECTING.set(SELECTING.get() + 1);
            }

            @Override protected void after(Hooks.Call call) {
                int depth = SELECTING.get() - 1;
                if (depth == 0) SELECTING.remove();
                else SELECTING.set(depth);
                if (call.hasThrowable() || APPLYING.get()) return;
                float value = (Float) call.args[0];
                Object source = call.args[3];
                if (explicitChoice(source)) {
                    if (Float.isFinite(value)) saveSpeed(clamp(value), call.thisObject);
                } else if (source == null && value == 1f && remember) {
                    // Releasing a temporary boost restores the chosen speed, without saving the boost.
                    state.apply(call.args[1], (String) call.args[2]);
                }
            }
        });
        Hooks.hook(restore, new Hooks.Hook() {
            @Override protected void after(Hooks.Call call) {
                if (remember && !call.hasThrowable()) state.apply(call.args[0], (String) call.args[1]);
            }
        });
        for (Method getter : getters) {
            Hooks.hook(getter, new Hooks.Hook() {
                @Override protected void after(Hooks.Call call) {
                    // These are current/previous speed readers. Preserve live boosts and explicit choices.
                    if (remember && SELECTING.get() == 0 && !call.hasThrowable()
                            && Float.valueOf(1f).equals(call.getResult()) && state.eligible(call.args[0])) {
                        call.setResult(getSpeed());
                    }
                }
            });
        }
        logOnce("remember-installed", "remember hooks installed; speed readers=" + getters.size());
    }

    private static boolean explicitChoice(Object source) {
        return "long_press".equals(source) || "click_share_button".equals(source)
                || "swipe_up_lock_persist".equals(source);
    }

    private static final class SpeedManager {
        final Method select;
        final Method gate;

        SpeedManager(Method select, Method gate) {
            this.select = select;
            this.gate = gate;
        }

        boolean eligible(Object aweme) {
            if (aweme == null || CHECKING.get()) return false;
            CHECKING.set(true);
            try {
                return Boolean.TRUE.equals(gate.invoke(null, aweme));
            } catch (Throwable error) {
                logOnce("eligibility", "remember skipped: eligibility invocation failed");
                return false;
            } finally {
                CHECKING.remove();
            }
        }

        void apply(Object aweme, String eventType) {
            if (APPLYING.get() || !eligible(aweme)) return;
            APPLYING.set(true);
            try {
                // The native selection path updates fields, player callbacks and menu state together.
                select.invoke(null, getSpeed(), aweme, eventType == null ? "" : eventType, "");
            } catch (Throwable error) {
                logOnce("apply", "remember skipped: native speed application failed");
            } finally {
                APPLYING.remove();
            }
        }
    }

    private static void installMenu(ClassLoader loader) {
        // The inline row and the separate speed sheet have independent list providers.
        try {
            installInlineMenu(loader);
        } catch (Throwable error) {
            logOnce("inline-install", "custom inline menu unavailable: " + error.getClass().getSimpleName());
        }
        try {
            installSheetMenu(loader);
        } catch (Throwable error) {
            logOnce("sheet-install", "custom speed sheet unavailable: " + error.getClass().getSimpleName());
        }
    }

    /** Values of the speed row in the long-press menu, asked each time the menu opens. */
    static volatile Method INLINE_PROVIDER;

    private static void installInlineMenu(ClassLoader loader) {
        Class<?> sharePackage = Hooks.findClass("com.ss.android.ugc.aweme.share.base.model.BaseSharePackage", loader);
        if (sharePackage == null) {
            logOnce("inline-install", "custom inline menu unavailable: BaseSharePackage missing");
            return;
        }
        Set<Method> providers = new LinkedHashSet<>();
        List<Method> builders = new ArrayList<>();
        for (Method marker : matching(loader, Discovery.methodsUsing("is_highlight_fast_speed"),
                false, void.class, sharePackage)) {
            if (!calls(marker).contains("android.os.BaseBundle#getBoolean")) continue;
            Class<?> row = marker.getDeclaringClass();
            for (Method provider : row.getDeclaredMethods()) {
                if (!signature(provider, false, List.class)) continue;
                String listCall = row.getName() + "#" + provider.getName();
                List<Method> rowBuilders = new ArrayList<>();
                boolean rebuilds = false;
                boolean creates = false;
                for (Method builder : row.getDeclaredMethods()) {
                    Class<?>[] p = builder.getParameterTypes();
                    boolean rebuild = signature(builder, false, void.class, Context.class, ViewGroup.class);
                    boolean create = !Modifier.isStatic(builder.getModifiers())
                            && !Modifier.isAbstract(builder.getModifiers())
                            && builder.getReturnType() == View.class && p.length == 3
                            && p[0] == ViewGroup.class && p[1] == Context.class;
                    if ((!rebuild && !create) || !calls(builder).contains(listCall)
                            || !calls(builder).contains("android.view.ViewGroup#addView")) continue;
                    // Both bind paths iterate this list, format each float, and add one view per value.
                    boolean formatsFloat = false;
                    for (Method formatter : row.getDeclaredMethods()) {
                        if (signature(formatter, false, String.class, float.class)
                                && calls(builder).contains(row.getName() + "#" + formatter.getName())
                                && calls(formatter).contains("java.lang.StringBuilder#append")) {
                            formatsFloat = true;
                        }
                    }
                    if (!formatsFloat) continue;
                    rowBuilders.add(builder);
                    rebuilds |= rebuild;
                    creates |= create;
                }
                if (rebuilds && creates) {
                    providers.add(provider);
                    builders.addAll(rowBuilders);
                }
            }
        }
        if (providers.size() != 1) {
            logOnce("inline-install", "custom inline menu unavailable: row list provider missing or ambiguous");
            return;
        }
        // A row can be reused. Freeze its values between binds so label/index lookups stay aligned
        // if configure() changes the option count while the old row is still on screen.
        for (Method builder : new LinkedHashSet<>(builders)) {
            Hooks.hook(builder, new Hooks.Hook() {
                @Override protected void before(Hooks.Call call) {
                    try {
                        Hooks.setExtra(call.thisObject, INLINE_SPEEDS, null);
                    } catch (Throwable error) {
                        logOnce("inline-bind", "custom inline menu skipped: row binding failed");
                    }
                }
            });
        }
        hookSpeedList(providers.iterator().next(), "inline", true);
        INLINE_PROVIDER = providers.iterator().next();
        logOnce("inline-installed", "custom inline menu hook installed");
    }

    private static void installSheetMenu(ClassLoader loader) {
        Set<Method> factories = new LinkedHashSet<>();
        Set<Method> experimentGetters = new LinkedHashSet<>();
        for (String entry : Discovery.methodsUsing(THREE_TIMES)) {
            String[] parts = entry.split("#", 2);
            if (parts.length != 2) continue;
            Class<?> type = Hooks.findClass(parts[0], loader);
            if (type == null) continue;
            factories.addAll(matching(loader, Collections.singleton(entry), true, List.class));
            experimentGetters.addAll(matching(loader, Collections.singleton(entry), true, boolean.class));
            // Kotlin's experiment value is a Function0<Boolean>. Its constructor is called by
            // the experiment holder's <clinit>; its only static ()boolean method exposes the value.
            boolean booleanSupplier = false;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(parts[1]) && signature(method, false, Object.class)
                        && calls(method).contains("java.lang.Boolean#valueOf")) booleanSupplier = true;
            }
            if (!booleanSupplier) continue;
            for (String caller : Discovery.methodsCalling(type.getName(), "<init>")) {
                String[] owner = caller.split("#", 2);
                if (owner.length != 2 || !"<clinit>".equals(owner[1])) continue;
                Class<?> holder = Hooks.findClass(owner[0], loader);
                if (holder == null) continue;
                List<Method> getters = new ArrayList<>();
                for (Method method : holder.getDeclaredMethods()) {
                    if (signature(method, true, boolean.class)) getters.add(method);
                }
                if (getters.size() == 1) experimentGetters.add(getters.get(0));
            }
        }
        for (Method getter : experimentGetters) {
            factories.addAll(matching(loader,
                    Discovery.methodsCalling(getter.getDeclaringClass().getName(), getter.getName()),
                    true, List.class));
        }
        if (factories.size() != 1) {
            logOnce("sheet-install", "custom speed sheet unavailable: list factory missing or ambiguous");
            return;
        }
        hookSpeedList(factories.iterator().next(), "sheet", false);
        logOnce("sheet-installed", "custom speed sheet hook installed");
    }

    private static void hookSpeedList(Method provider, String surface, boolean inline) {
        Hooks.hook(provider, new Hooks.Hook() {
            @Override protected void after(Hooks.Call call) {
                try {
                    if (call.hasThrowable()) return;
                    String configured = speeds;
                    boolean custom = configured != null && !configured.trim().isEmpty();
                    if (!inline && !custom) return;
                    if (!nativeSpeedList(call.getResult())) {
                        logOnce(surface + "-shape", "custom " + surface + " menu skipped: unexpected native speed list");
                        return;
                    }
                    if (inline) {
                        Object snapshot = Hooks.getExtra(call.thisObject, INLINE_SPEEDS);
                        if (snapshot instanceof List<?>) {
                            call.setResult(new ArrayList<>((List<?>) snapshot));
                            return;
                        }
                        logOnce("inline-reached", "inline menu speed provider reached");
                        if (!custom) {
                            Hooks.setExtra(call.thisObject, INLINE_SPEEDS, new ArrayList<>((List<?>) call.getResult()));
                            return;
                        }
                    }
                    ParsedSpeeds current = parsed;
                    if (!configured.equals(current.input)) {
                        current = new ParsedSpeeds(configured,
                                Collections.unmodifiableList(parseSpeeds(configured)));
                        parsed = current;
                    }
                    if (inline) Hooks.setExtra(call.thisObject, INLINE_SPEEDS, current.values);
                    // Return a fresh mutable list: native callers may sort or remove an option.
                    call.setResult(new ArrayList<>(current.values));
                    logOnce(surface + "-replaced", "custom " + surface + " menu applied; options=" + current.values.size());
                } catch (Throwable error) {
                    logOnce(surface + "-callback", "custom " + surface + " menu skipped: list replacement failed");
                }
            }
        });
    }

    private static boolean nativeSpeedList(Object result) {
        if (!(result instanceof List<?>)) return false;
        List<?> values = (List<?>) result;
        return values.equals(DEFAULT_SPEEDS) || values.equals(Arrays.asList(0.5f, 1f, 1.5f, 2f, 3f));
    }

    private static final class ParsedSpeeds {
        final String input;
        final List<Float> values;

        ParsedSpeeds(String input, List<Float> values) {
            this.input = input;
            this.values = values;
        }
    }

    private static Set<Method> matching(ClassLoader loader, Iterable<String> entries,
                                        boolean isStatic, Class<?> result, Class<?>... parameters) {
        Set<Method> matches = new LinkedHashSet<>();
        for (String entry : entries) {
            String[] parts = entry.split("#", 2);
            if (parts.length != 2) continue;
            Class<?> type = Hooks.findClass(parts[0], loader);
            if (type == null) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(parts[1]) && signature(method, isStatic, result, parameters)) {
                    matches.add(method);
                }
            }
        }
        return matches;
    }

    private static boolean signature(Method method, boolean isStatic, Class<?> result,
                                     Class<?>... parameters) {
        return Modifier.isStatic(method.getModifiers()) == isStatic
                && !Modifier.isAbstract(method.getModifiers()) && method.getReturnType() == result
                && Arrays.equals(method.getParameterTypes(), parameters);
    }

    private static List<String> calls(Method method) {
        return Discovery.invokedBy(method.getDeclaringClass().getName(), method.getName());
    }

    private static float clamp(float value) {
        return Math.max(0.25f, Math.min(3f, value));
    }

    private static synchronized SharedPreferences preferences(Object source) {
        if (preferences != null) return preferences;
        try {
            Context context = context(source);
            if (context == null) {
                logOnce("preferences-context", "speed storage waits for the application context");
                return null;
            }
            Context app = context.getApplicationContext();
            SharedPreferences prefs = (app == null ? context : app).getSharedPreferences(FILE, Context.MODE_PRIVATE);
            if (Float.isFinite(chosenSpeed)) prefs.edit().putFloat(KEY, chosenSpeed).apply();
            else readSpeed(prefs);
            preferences = prefs;
            return prefs;
        } catch (Throwable error) {
            logOnce("preferences", "speed storage unavailable: " + error.getClass().getSimpleName());
            return null;
        }
    }

    private static Context context(Object source) throws Throwable {
        if (source instanceof Context) return (Context) source;
        if (source instanceof View) return ((View) source).getContext();
        if (source != null) {
            for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || !Context.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(source);
                    if (value instanceof Context) return (Context) value;
                }
            }
        }
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Method application = thread.getDeclaredMethod("currentApplication");
        application.setAccessible(true);
        Object value = application.invoke(null);
        return value instanceof Context ? (Context) value : null;
    }

    private static void readSpeed(SharedPreferences prefs) {
        try {
            float value = prefs.getFloat(KEY, 1f);
            chosenSpeed = Float.isFinite(value) ? clamp(value) : 1f;
        } catch (Throwable error) {
            chosenSpeed = 1f;
            logOnce("preferences-read", "saved speed unavailable; using 1x");
        }
    }

    private static synchronized void saveSpeed(float value, Object source) {
        SharedPreferences prefs = preferences(source);
        chosenSpeed = value;
        if (prefs == null) return;
        try {
            prefs.edit().putFloat(KEY, value).apply();
        } catch (Throwable error) {
            logOnce("preferences-write", "speed persistence unavailable; keeping the in-memory choice");
        }
    }

    private static void logOnce(String key, String message) {
        if (!LOGGED.add(key)) return;
        try {
            RuntimeLog.log("playback speed: " + message);
        } catch (Throwable ignored) { }
    }
}
