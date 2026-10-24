package com.golda.patchertiktok;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A favourite sticker instead of the flame for auto streaks. The sticker goes out the way the chat's
 * sticker panel sends one from its Favourites tab: TikTok's sticker message service, its own sticker
 * model and the panel's source. Everything is found by readable names and parameter types.
 */
final class StreakSticker {
    private static final String API = "com.ss.android.ugc.aweme.im.sticker.api.IMStickerApi";
    private static final String HELPER = "com.ss.android.ugc.aweme.im.sticker.basic.stickerstore.BasicStickerStoreHelper";
    private static final String ITEM = "com.ss.android.ugc.aweme.im.common.model.StickerItem";
    private static final String FAVOURITE_SOURCE = "FAVOURITE_TAB";
    private static final String PANEL_SOURCE = "DM_STICKER_PANEL";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The enum TikTok names send sources with; found once while the dex index is open. */
    private static volatile List<String> sourceEnums = Collections.emptyList();
    /** TikTok's class loader, for the settings page that lists the favourites. */
    private static volatile ClassLoader host;

    static final class Choice {
        final String id;
        final String title;
        /** Mirrors of one preview picture, best first. */
        final List<String> previews;

        Choice(String id, String title, List<String> previews) {
            this.id = id;
            this.title = title;
            this.previews = previews;
        }
    }

    private StreakSticker() { }

    /** At startup, with {@link Discovery} open: remember the class of the panel's send sources. */
    static void prepare(ClassLoader loader) {
        host = loader;
        // Analytics also use the name; the right class is the enum the send method takes, chosen later.
        List<String> names = new ArrayList<>(Discovery.classesUsing(PANEL_SOURCE));
        sourceEnums = names;
        if (names.isEmpty()) RuntimeLog.log("streak sticker unavailable: sticker send source not found");
    }


    /** The chosen sticker's id, or null for TikTok's flame. */
    static String chosen() {
        String id = Prefs.text(Prefs.STREAK_STICKER);
        return id == null || id.isEmpty() ? null : id;
    }

    /** Favourite stickers, as the Favourites tab lists them; null outside TikTok. Off the main thread. */
    static List<Choice> favourites() {
        ClassLoader loader = host;
        if (loader == null) return null;
        List<Choice> result = new ArrayList<>();
        for (Object item : items(loader, true)) {
            Object base = Reflect.get(item, "stickerBase");
            Object id = Reflect.get(base, "id");
            if (id == null) continue;
            Object title = Reflect.get(base, "title");
            List<String> previews = new ArrayList<>(addresses(Reflect.get(base, "image")));
            for (String url : addresses(Reflect.get(base, "thumbnail"))) if (!previews.contains(url)) previews.add(url);
            result.add(new Choice(String.valueOf(id), title instanceof String ? (String) title : "", previews));
        }
        return result;
    }

    /**
     * Sends the chosen sticker into the conversation. Off the main thread; null when it cannot be
     * sent (no longer a favourite, service changed), so the caller sends the flame instead.
     */
    static Boolean send(Context context, ClassLoader loader, String conversation, String id) {
        Object item = null;
        for (Object candidate : items(loader, false)) {
            if (id.equals(String.valueOf(Reflect.get(Reflect.get(candidate, "stickerBase"), "id")))) item = candidate;
        }
        if (item == null) {
            for (Object candidate : items(loader, true)) {
                if (id.equals(String.valueOf(Reflect.get(Reflect.get(candidate, "stickerBase"), "id")))) item = candidate;
            }
        }
        if (item == null) {
            RuntimeLog.log("streak sticker skipped: sticker is no longer a favourite");
            return null;
        }
        try {
            Class<?> itemType = Class.forName(ITEM, false, loader);
            Object api = StreakApi.service(loader, API);
            Object service = api.getClass().getMethod("getImStickerMessageService").invoke(api);
            Method method = sendMethod(service.getClass(), itemType);
            if (method == null) {
                RuntimeLog.log("streak sticker unavailable: sticker send method not recognised");
                return null;
            }
            Object[] args = arguments(method, context.getApplicationContext(), item, conversation, loader);
            if (args == null) return null;
            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            MAIN.post(() -> {
                try {
                    method.invoke(service, args);
                } catch (Throwable error) {
                    failure.set(error);
                } finally {
                    done.countDown();
                }
            });
            if (!done.await(10, TimeUnit.SECONDS)) return Boolean.FALSE;
            if (failure.get() != null) {
                RuntimeLog.log("streak sticker failed: " + failure.get().getClass().getSimpleName());
                return null;
            }
            return Boolean.TRUE;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Boolean.FALSE;
        } catch (Throwable error) {
            RuntimeLog.log("streak sticker unavailable: " + error.getClass().getSimpleName());
            return null;
        }
    }

    /** The one public method taking a context, a sticker item and a conversation id. */
    private static Method sendMethod(Class<?> type, Class<?> itemType) {
        Method found = null;
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            boolean context = false;
            boolean item = false;
            int strings = 0;
            for (Class<?> param : params) {
                context |= param == Context.class;
                item |= param == itemType;
                if (param == String.class) strings++;
            }
            if (!context || !item || strings != 1 || method.getReturnType() != void.class) continue;
            if (found != null) return null;
            found = method;
        }
        return found;
    }

    private static Object[] arguments(Method method, Context context, Object item, String conversation, ClassLoader loader)
            throws ClassNotFoundException {
        Class<?>[] params = method.getParameterTypes();
        Object[] args = new Object[params.length];
        Object source = null;
        for (int i = 0; i < params.length; i++) {
            Class<?> param = params[i];
            if (param == Context.class) args[i] = context;
            else if (param.isInstance(item)) args[i] = item;
            else if (param == String.class) args[i] = conversation;
            else if (param == boolean.class) args[i] = Boolean.TRUE;
            else if (param.isPrimitive()) return null;
            else if (source == null && (source = source(param, loader)) != null) args[i] = source;
        }
        if (source == null) {
            RuntimeLog.log("streak sticker unavailable: send source not recognised");
            return null;
        }
        return args;
    }

    /** "Favourites tab" from TikTok's source enum when this parameter takes it. */
    private static Object source(Class<?> param, ClassLoader loader) {
        for (String name : sourceEnums) {
            Class<?> type;
            try {
                type = Class.forName(name, false, loader);
            } catch (ClassNotFoundException missing) {
                continue;
            }
            if (!type.isEnum() || !param.isAssignableFrom(type)) continue;
            Object fallback = null;
            for (Object constant : type.getEnumConstants()) {
                String constantName = ((Enum<?>) constant).name();
                if (FAVOURITE_SOURCE.equals(constantName)) return constant;
                if (PANEL_SOURCE.equals(constantName)) fallback = constant;
            }
            if (fallback != null) return fallback;
        }
        return null;
    }

    /** TikTok's favourite sticker items; with {@code refresh}, asks the store to reload them first. */
    private static List<Object> items(ClassLoader loader, boolean refresh) {
        try {
            Object store = store(loader);
            if (store == null) return Collections.emptyList();
            Class<?> itemType = Class.forName(ITEM, false, loader);
            Method flow = store.getClass().getMethod("getFavouriteStickersFlow");
            Object before = Reflect.call(flow.invoke(store), "getValue");
            if (!refresh) return collect(before, itemType);
            try {
                store.getClass().getMethod("refreshFavouriteStickers", boolean.class, boolean.class, String.class)
                        .invoke(store, true, true, "favourite_tab");
            } catch (NoSuchMethodException ignored) {
                return collect(before, itemType);
            }
            // The cached list can be old, with expired picture links; wait for the reloaded one.
            long deadline = SystemClock.uptimeMillis() + 6_000L;
            while (SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(250);
                Object value = Reflect.call(flow.invoke(store), "getValue");
                if (value != before && !collect(value, itemType).isEmpty()) return collect(value, itemType);
            }
            return collect(Reflect.call(flow.invoke(store), "getValue"), itemType);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (Throwable error) {
            RuntimeLog.log("streak sticker favourites unavailable: " + error.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static Object store(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> helper = Class.forName(HELPER, false, loader);
        Object instance = null;
        for (Field field : helper.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == helper) {
                field.setAccessible(true);
                instance = field.get(null);
            }
        }
        if (instance == null) return null;
        for (Method method : helper.getDeclaredMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())) continue;
            try {
                method.getReturnType().getMethod("getFavouriteStickersFlow");
            } catch (NoSuchMethodException other) {
                continue;
            }
            method.setAccessible(true);
            return method.invoke(instance);
        }
        return null;
    }

    /**
     * Sticker items in the flow's value: a list of them (or of wrappers that hold one), possibly
     * inside a load result such as {@code Success(data=[...])}.
     */
    private static List<Object> collect(Object value, Class<?> itemType) {
        List<Object> items = new ArrayList<>();
        if (value != null && !(value instanceof List)) value = listInside(value);
        if (!(value instanceof List)) return items;
        for (Object entry : (List<?>) value) {
            if (itemType.isInstance(entry)) {
                items.add(entry);
                continue;
            }
            Object inner = nested(entry, itemType);
            if (inner != null) items.add(inner);
        }
        return items;
    }

    private static Object listInside(Object holder) {
        Object data = Reflect.get(holder, "data");
        if (data instanceof List) return data;
        for (Class<?> type = holder.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                // Kotlin's generic result keeps the list in a field declared as Object.
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(holder);
                    if (value instanceof List) return value;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static Object nested(Object start, Class<?> itemType) {
        if (start == null) return null;
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{start, 0});
        while (!queue.isEmpty() && seen.size() < 40) {
            Object[] entry = queue.poll();
            Object value = entry[0];
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            if (itemType.isInstance(value)) return value;
            if ((Integer) entry[1] >= 2) continue;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                if (type.getName().startsWith("java.")) break;
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    try {
                        field.setAccessible(true);
                        queue.add(new Object[]{field.get(value), (Integer) entry[1] + 1});
                    } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    /** A sticker picture's addresses: its url list, or the first one found a few fields down. */
    private static List<String> addresses(Object image) {
        List<String> urls = new ArrayList<>();
        if (image == null) return urls;
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{image, 0});
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        while (!queue.isEmpty() && seen.size() < 30) {
            Object[] entry = queue.poll();
            Object value = entry[0];
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            Object list = Reflect.get(value, "urlList");
            if (list instanceof List) {
                for (Object url : (List<?>) list) {
                    if (url instanceof String && DownloadFormat.webUrl((String) url)) urls.add((String) url);
                }
                if (!urls.isEmpty()) return urls;
            }
            if ((Integer) entry[1] >= 2) continue;
            for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                if (type.getName().startsWith("java.")) break;
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                            || field.getType() == String.class) continue;
                    try {
                        field.setAccessible(true);
                        queue.add(new Object[]{field.get(value), (Integer) entry[1] + 1});
                    } catch (Throwable ignored) { }
                }
            }
        }
        return urls;
    }
}
