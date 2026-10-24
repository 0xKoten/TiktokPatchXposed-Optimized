package com.golda.patchertiktok;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Install after Discovery.init, before Discovery.release. Disabled until configured.
 * Uses TikTok's native presenter, cache, request, eligibility and observer updates. All dex
 * lookup runs at installation; bound cells only use cached reflection on the main thread.
 * Queue <=64 weak cell references; attempt cache <=512 IDs, expiring after ten minutes.
 */
final class CommentTranslate {
    static volatile boolean enabled;
    static volatile String excludedLanguages = "";
    static volatile int maxPerSecond = 2;

    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final String CELL = "com.ss.android.ugc.aweme.commentv2.commentlist.powercell.BaseCommentCell";
    private static final String POWER_CELL = "com.bytedance.ies.powerlist.PowerCell";
    private static final String LIVE_DATA = "com.ss.android.ugc.aweme.arch.widgets.base.NextLiveData";
    private static final String BODY = "com.ss.android.ugc.aweme.comment.translation.MultiTranslationBody";
    private static final String TRANSLATION = "com.ss.android.ugc.aweme.translation.service.ITranslationService";
    private static final String SETTINGS = "com.ss.android.ugc.aweme.setting.services.ISettingService";
    private static final int QUEUE_LIMIT = 64;
    private static final long DEBOUNCE_MS = 250;
    private static final CommentTranslatePolicy.Gate GATE = new CommentTranslatePolicy.Gate(512, 600000);
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicBoolean UNSUPPORTED = new AtomicBoolean();
    private static final LinkedHashMap<String, Pending> PENDING = new LinkedHashMap<>();
    private static volatile Exclusions exclusions = new Exclusions("");
    private static volatile long generation;
    private static volatile boolean ready;
    private static boolean installed;
    private static Handler handler;
    private static boolean scheduled;
    private static Anchors anchors;
    private static final Runnable DRAIN = CommentTranslate::drain;

    private CommentTranslate() { }

    static synchronized void configure(boolean autoTranslate, String excluded) {
        configure(autoTranslate, excluded, 2);
    }

    /** perSecond is clamped to [1,5]; direct volatile-field writes are also accepted. */
    static synchronized void configure(boolean autoTranslate, String excluded, int perSecond) {
        excludedLanguages = excluded == null ? "" : excluded;
        exclusions = new Exclusions(excludedLanguages);
        maxPerSecond = CommentTranslatePolicy.rate(perSecond);
        generation++;
        enabled = autoTranslate;
    }

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        installed = true;
        try {
            anchors = discover(loader);
            handler = new Handler(Looper.getMainLooper());
            Hooks.Hook visible = new Hooks.Hook() {
                @Override protected void after(Hooks.Call call) {
                    if (!ready || !enabled || call.hasThrowable()) return;
                    try { bound(call.thisObject); }
                    catch (Throwable error) { fail("cell binding changed", error); }
                }
            };
            Hooks.hook(anchors.bind, visible);
            // RecyclerView can prefetch a row before it becomes visible.
            Hooks.hook(anchors.attach, visible);
            // Native manual translation and "See original" remain authoritative for this ID.
            Hooks.Hook manual = new Hooks.Hook() {
                @Override protected void before(Hooks.Call call) {
                    if (!ready || !enabled) return;
                    try {
                        Object comment = anchors.presenterComment.get(call.thisObject);
                        if (comment != null) {
                            GATE.remember(key(comment, anchors.targetLanguage()), SystemClock.elapsedRealtime());
                        }
                    } catch (Throwable error) { fail("manual action changed", error); }
                }
            };
            Hooks.hook(anchors.translate, manual);
            Hooks.hook(anchors.reset, manual);
            ready = true;
            RuntimeLog.log("comment auto-translate: native cell hook installed");
            RuntimeLog.log("comment auto-translate: native switch hooks=" + hookNativeSwitch(loader));
        } catch (Throwable error) {
            fail("anchors missing or ambiguous; feature skipped", error);
        }
    }

    /** Log text in TikTok's inline "See translation" renderer of the newer comment translation. */
    private static final String INLINE_BUTTON = "addTranslateStatusButton, aid:";
    private static final String CAPTION_KEVA = "com.ss.android.ugc.aweme.service.ICaptionKevaService";
    /** True once TikTok drew its inline translation button: its own translation runs the cells. */
    private static volatile boolean inline;

    /**
     * The newer comment translation shows its button, and translates on its own, only while
     * TikTok's "Translate comments" switch is on. The renderer asks the caption service for it;
     * that service reads one static switch, which is turned on while auto-translate is.
     */
    private static int hookNativeSwitch(ClassLoader loader) throws Throwable {
        Class<?> keva = required(CAPTION_KEVA, loader);
        Set<String> asked = new LinkedHashSet<>();
        for (String renderer : Discovery.methodsUsing(INLINE_BUTTON)) {
            String[] parts = renderer.split("#", 2);
            Class<?> owner = Hooks.findClass(parts[0], loader);
            if (owner == null) continue;
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(parts[1])) continue;
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call call) { inline = true; }
                });
            }
            for (String call : Discovery.invokedBy(parts[0], parts[1])) {
                if (call.startsWith(CAPTION_KEVA + "#")) asked.add(call.substring(CAPTION_KEVA.length() + 1));
            }
        }
        Class<?> services = required("com.ss.android.ugc.aweme.framework.services.ServiceManager", loader);
        Object impl = services.getMethod("getService", Class.class).invoke(services.getMethod("get").invoke(null), keva);
        if (impl == null) return 0;
        int hooks = 0;
        for (String name : asked) {
            Method getter;
            try {
                getter = impl.getClass().getMethod(name);
            } catch (NoSuchMethodException missing) { continue; }
            if (getter.getReturnType() != boolean.class) continue;
            // The service only forwards; the switch itself is the one static (): boolean it reads.
            for (Method target : resolve(loader, Discovery.invokedBy(impl.getClass().getName(), name))) {
                if (!Modifier.isStatic(target.getModifiers()) || target.getReturnType() != boolean.class
                        || target.getParameterCount() != 0) continue;
                Hooks.hook(target, new Hooks.Hook() {
                    @Override protected void after(Hooks.Call call) {
                        if (enabled && !Boolean.TRUE.equals(call.getResult())) call.setResult(true);
                    }
                });
                hooks++;
            }
        }
        return hooks;
    }

    private static void bound(Object cell) throws Throwable {
        // TikTok's own translation is on and draws these cells; the older presenter would fight it.
        if (inline || Looper.myLooper() != Looper.getMainLooper()) return;
        View view = (View) anchors.itemView.get(cell);
        Object presenter = anchors.presenter(cell);
        if (presenter == null) return; // TikTok can choose another presenter for its experiment.
        Object comment = anchors.presenterComment.get(presenter);
        if (comment == null) return;
        String target = anchors.targetLanguage();
        String id = key(comment, target);
        if (!eligible(comment, target) || GATE.seen(id, SystemClock.elapsedRealtime())) return;
        // Rebinds coalesce by ID. Replacement tracks the latest cell without retaining its view.
        if (!PENDING.containsKey(id) && PENDING.size() >= QUEUE_LIMIT) return;
        PENDING.put(id, new Pending(cell, comment, view, target, generation,
                SystemClock.elapsedRealtime() + DEBOUNCE_MS));
        schedule(DEBOUNCE_MS);
    }

    private static boolean eligible(Object comment, String target) throws Throwable {
        if (!enabled || !ready || Boolean.TRUE.equals(anchors.translated.invoke(comment))) return false;
        if (Boolean.FALSE.equals(anchors.translatable.invoke(comment))) return false;
        String app = (String) anchors.appLanguage.invoke(anchors.settingService);
        // TikTok has a separate preferred translation language. Do not change that global setting.
        // Only invoke its action when that language agrees with the app language.
        String appCode = CommentTranslatePolicy.language(app);
        if (appCode.isEmpty() || !appCode.equals(CommentTranslatePolicy.language(target))) {
            if (UNSUPPORTED.compareAndSet(false, true)) {
                RuntimeLog.log("comment auto-translate skipped: native target differs from app language or language missing");
            }
            return false;
        }
        String configured = excludedLanguages == null ? "" : excludedLanguages;
        Exclusions current = exclusions;
        if (!configured.equals(current.input)) exclusions = current = new Exclusions(configured);
        return CommentTranslatePolicy.eligible((String) anchors.language.invoke(comment), app, current.values);
    }

    private static String key(Object comment, String target) throws Throwable {
        return CommentTranslatePolicy.key((String) anchors.cid.invoke(comment), target);
    }

    private static void schedule(long delay) {
        if (scheduled) return;
        scheduled = handler.postDelayed(DRAIN, Math.max(1, delay));
    }

    private static void drain() {
        scheduled = false;
        if (!ready || !enabled) { PENDING.clear(); return; }
        try {
            long now = SystemClock.elapsedRealtime();
            Iterator<java.util.Map.Entry<String, Pending>> iterator = PENDING.entrySet().iterator();
            while (iterator.hasNext()) {
                java.util.Map.Entry<String, Pending> entry = iterator.next();
                Pending pending = entry.getValue();
                Object cell = pending.cell.get();
                Object comment = pending.comment.get();
                View view = pending.view.get();
                Object presenter = cell == null ? null : anchors.presenter(cell);
                if (pending.generation != generation || comment == null || view == null || presenter == null
                        || anchors.presenterComment.get(presenter) != comment
                        || !pending.target.equals(anchors.targetLanguage())
                        || !eligible(comment, pending.target) || GATE.seen(entry.getKey(), now)) {
                    iterator.remove();
                    continue;
                }
                if (now < pending.due) { schedule(pending.due - now); return; }
                // Binding happens before attachment. The debounce gives attachment a chance;
                // offscreen/recycled rows are dropped, and will be considered on their next bind.
                if (!view.isAttachedToWindow() || !view.isShown() || view.getWindowVisibility() != View.VISIBLE
                        || !view.getGlobalVisibleRect(new Rect())) {
                    iterator.remove();
                    continue;
                }
                long delay = GATE.delay(now, maxPerSecond);
                if (delay > 0) { schedule(delay); return; }
                String id = entry.getKey();
                iterator.remove();
                if (GATE.acquire(id, now, maxPerSecond)) {
                    // The native action includes its own in-flight gate, cache and async service.
                    // Count even synchronous failures; scrolling must not create a retry storm.
                    anchors.translate.invoke(presenter);
                }
                if (!PENDING.isEmpty()) schedule(Math.max(1, GATE.delay(SystemClock.elapsedRealtime(), maxPerSecond)));
                return;
            }
        } catch (Throwable error) { fail("native action failed; feature stopped", error); }
    }

    private static Anchors discover(ClassLoader loader) throws Throwable {
        Class<?> comment = required(COMMENT, loader);
        Class<?> cell = required(CELL, loader);
        Class<?> observer = required("androidx.lifecycle.Observer", loader);
        Class<?> liveData = required(LIVE_DATA, loader);
        Class<?> translation = required(TRANSLATION, loader);
        Class<?> setting = required(SETTINGS, loader);
        Method bind = null;
        for (Method method : cell.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                    || method.getParameterCount() != 1 || Modifier.isAbstract(method.getModifiers())) continue;
            List<String> calls = calls(method);
            if (!calls.contains(POWER_CELL + "#onBindItemView")
                    || !calls.contains(COMMENT + "#getCommentLanguage")
                    || !calls.contains(LIVE_DATA + "#observe")) continue;
            if (bind != null) throw new IllegalStateException("Ambiguous comment bind");
            bind = method;
        }
        if (bind == null) throw new NoSuchMethodException("Comment bind");
        Method attach = cell.getDeclaredMethod("onViewAttachedToWindow");
        if (!signature(attach, false, void.class)) throw new NoSuchMethodException("Cell attachment");

        // The bind subscribes to a (String)->NextLiveData cache provider. Its owner also
        // dispatches (List<Comment>, metadata, boolean) through MultiTranslationBody.
        Set<Method> dispatchers = new LinkedHashSet<>();
        for (Method provider : resolve(loader, calls(bind))) {
            if (!signature(provider, true, liveData, String.class)) continue;
            for (Method method : provider.getDeclaringClass().getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class
                        || p.length != 3 || p[0] != List.class || p[2] != boolean.class) continue;
                List<String> invoked = calls(method);
                if (invoked.contains(COMMENT + "#clone") && invoked.contains(BODY + "#<init>")
                        && invoked.contains("androidx.lifecycle.LiveData#setValue")) dispatchers.add(method);
            }
        }
        Method dispatch = only(dispatchers, "Native translation dispatch");
        Set<Method> actions = new LinkedHashSet<>();
        for (Method caller : resolve(loader, Discovery.methodsCalling(
                dispatch.getDeclaringClass().getName(), dispatch.getName()))) {
            if (!caller.getName().equals("translate") || !signature(caller, false, void.class)
                    || !observer.isAssignableFrom(caller.getDeclaringClass())
                    || caller.getDeclaringClass().getSuperclass() != Object.class) continue;
            if (uniqueField(caller.getDeclaringClass(), comment) != null
                    && uniqueField(caller.getDeclaringClass(), Context.class) != null
                    && calls(caller).contains("java.util.Collections#singletonList")) actions.add(caller);
        }
        Method action = only(actions, "Native comment presenter");
        Class<?> presenter = action.getDeclaringClass();
        Method reset = presenter.getDeclaredMethod("resetTranslate");
        if (!signature(reset, false, void.class)) throw new NoSuchMethodException("Reset action");
        Field model = uniqueField(presenter, comment);
        Field controller = null;
        Field presenterField = null;
        for (Field field : cell.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            for (Field nested : field.getType().getDeclaredFields()) {
                if (Modifier.isStatic(nested.getModifiers()) || !nested.getType().isInterface()
                        || !nested.getType().isAssignableFrom(presenter)) continue;
                try {
                    Method translate = nested.getType().getMethod("translate");
                    Method original = nested.getType().getMethod("resetTranslate");
                    if (translate.getReturnType() != void.class || translate.getParameterCount() != 0
                            || original.getReturnType() != void.class || original.getParameterCount() != 0) continue;
                } catch (NoSuchMethodException missing) { continue; }
                if (controller != null) throw new IllegalStateException("Ambiguous presenter path");
                controller = field;
                presenterField = nested;
            }
        }
        if (controller == null) throw new NoSuchFieldException("Presenter path");

        // Derive the preferred target getter from the native request object's constructor,
        // not from the obfuscated interface method name or a resource ID.
        Set<Method> targets = new LinkedHashSet<>();
        for (String entry : calls(dispatch)) {
            String[] parts = entry.split("#", 2);
            if (parts.length != 2 || !parts[1].equals("<init>")) continue;
            Class<?> request = Hooks.findClass(parts[0], loader);
            if (request == null) continue;
            boolean requestShape = false;
            for (java.lang.reflect.Constructor<?> constructor : request.getDeclaredConstructors()) {
                if (Arrays.equals(constructor.getParameterTypes(),
                        new Class<?>[]{List.class, dispatch.getParameterTypes()[1]})) requestShape = true;
            }
            if (!requestShape) continue;
            for (Method target : resolve(loader, Discovery.invokedBy(request.getName(), "<init>"))) {
                if (target.getDeclaringClass() == translation && !Modifier.isStatic(target.getModifiers())
                        && target.getReturnType() == String.class && target.getParameterCount() == 0) targets.add(target);
            }
        }
        Method target = only(targets, "Native target language getter");
        Method app = setting.getMethod("getAppLanguage");
        if (app.getReturnType() != String.class) throw new NoSuchMethodException("App language");
        Class<?> services = required("com.ss.android.ugc.aweme.framework.services.ServiceManager", loader);
        Object manager = services.getMethod("get").invoke(null);
        Method service = services.getMethod("getService", Class.class);
        Object translationService = service.invoke(manager, translation);
        Object settingService = service.invoke(manager, setting);
        if (!translation.isInstance(translationService) || !setting.isInstance(settingService)) {
            throw new IllegalStateException("Language services missing");
        }
        return new Anchors(bind, attach, action, reset, controller, presenterField, model,
                cell.getField("itemView"), comment.getMethod("getCid"), comment.getMethod("getCommentLanguage"),
                comment.getMethod("isTranslated"), comment.getMethod("isCommentTranslatable"),
                target, app, translationService, settingService);
    }

    private static Class<?> required(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> result = Hooks.findClass(name, loader);
        if (result == null) throw new ClassNotFoundException(name);
        return result;
    }

    private static Field uniqueField(Class<?> type, Class<?> fieldType) {
        Field result = null;
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType) continue;
            if (result != null) return null;
            result = field;
        }
        return result;
    }

    private static boolean signature(Method method, boolean isStatic, Class<?> result, Class<?>... params) {
        return Modifier.isStatic(method.getModifiers()) == isStatic
                && !Modifier.isAbstract(method.getModifiers()) && method.getReturnType() == result
                && Arrays.equals(method.getParameterTypes(), params);
    }

    private static List<String> calls(Method method) {
        return Discovery.invokedBy(method.getDeclaringClass().getName(), method.getName());
    }

    private static Set<Method> resolve(ClassLoader loader, Iterable<String> entries) {
        Set<Method> result = new LinkedHashSet<>();
        for (String entry : entries) {
            String[] parts = entry.split("#", 2);
            if (parts.length != 2 || parts[1].startsWith("<")) continue;
            Class<?> type = Hooks.findClass(parts[0], loader);
            if (type == null) continue;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(parts[1])) result.add(method);
            }
        }
        return result;
    }

    private static Method only(Set<Method> methods, String label) throws NoSuchMethodException {
        if (methods.size() != 1) throw new NoSuchMethodException(label + ": " + methods.size());
        return methods.iterator().next();
    }

    private static void fail(String message, Throwable error) {
        ready = false;
        if (Looper.myLooper() == Looper.getMainLooper()) PENDING.clear();
        if (FAILED.compareAndSet(false, true)) {
            try { RuntimeLog.log("comment auto-translate unavailable: " + message + " ("
                    + error.getClass().getSimpleName() + ")"); }
            catch (Throwable ignored) { }
        }
    }

    private static final class Exclusions {
        final String input;
        final Set<String> values;
        Exclusions(String input) { this.input = input; values = CommentTranslatePolicy.exclusions(input); }
    }

    private static final class Pending {
        final WeakReference<Object> cell;
        final WeakReference<Object> comment;
        final WeakReference<View> view;
        final String target;
        final long generation;
        final long due;

        Pending(Object cell, Object comment, View view, String target, long generation, long due) {
            this.cell = new WeakReference<>(cell);
            this.comment = new WeakReference<>(comment);
            this.view = new WeakReference<>(view);
            this.target = target;
            this.generation = generation;
            this.due = due;
        }
    }

    private static final class Anchors {
        final Method bind, attach, translate, reset, cid, language, translated, translatable, target, appLanguage;
        final Field controller, presenterField, presenterComment, itemView;
        final Object translationService, settingService;

        Anchors(Method bind, Method attach, Method translate, Method reset, Field controller, Field presenterField,
                Field presenterComment, Field itemView, Method cid, Method language, Method translated,
                Method translatable, Method target, Method appLanguage, Object translationService,
                Object settingService) {
            this.bind = bind;
            this.attach = attach;
            this.translate = translate;
            this.reset = reset;
            this.controller = controller;
            this.presenterField = presenterField;
            this.presenterComment = presenterComment;
            this.itemView = itemView;
            this.cid = cid;
            this.language = language;
            this.translated = translated;
            this.translatable = translatable;
            this.target = target;
            this.appLanguage = appLanguage;
            this.translationService = translationService;
            this.settingService = settingService;
            if (!signature(cid, false, String.class) || !signature(language, false, String.class)
                    || !signature(translated, false, boolean.class)
                    || !signature(translatable, false, Boolean.class) || itemView.getType() != View.class) {
                throw new IllegalStateException("Comment model or item view signature changed");
            }
            for (Method method : Arrays.asList(bind, attach, translate, reset, cid, language, translated,
                    translatable, target, appLanguage)) method.setAccessible(true);
            for (Field field : Arrays.asList(controller, presenterField, presenterComment, itemView)) {
                field.setAccessible(true);
            }
        }

        Object presenter(Object cell) throws IllegalAccessException {
            Object holder = controller.get(cell);
            if (holder == null) return null;
            Object value = presenterField.get(holder);
            return translate.getDeclaringClass().isInstance(value) ? value : null;
        }

        String targetLanguage() throws Throwable { return (String) target.invoke(translationService); }
    }
}
