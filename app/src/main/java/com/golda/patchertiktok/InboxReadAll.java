package com.golda.patchertiktok;

import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Read all": holding the bottom Inbox tab marks every chat read, the way TikTok's own bulk action
 * does it: unread counts and badges go to zero on this phone and the read state is sent to TikTok's
 * server in batches. The chats are the current account's ones in TikTok's local chat store; TikTok
 * itself skips those already read. Install after Discovery.init.
 */
final class InboxReadAll {
    private static final String TAB = "com.ss.android.ugc.aweme.hometab.base.BottomTabNode";
    private static final String INBOX_TAG = "NOTIFICATION";
    private static final String CORE_SERVICE = "com.bytedance.ies.im.core.api.proxy.IIMCoreProxyService";
    /** Literal of the chat store's "all conversations" query. */
    private static final String ALL_QUERY = "IMConversationDao getAllConversation sql = ";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private static ClassLoader loader;
    private static Class<?> store;
    private static Method allChats;

    private InboxReadAll() { }

    static void install(ClassLoader classLoader) {
        loader = classLoader;
        Class<?> tab = Hooks.findClass(TAB, classLoader);
        store = Discovery.firstClass(classLoader, ALL_QUERY);
        if (store != null) {
            for (Method method : store.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 5 && p[0] == int.class && p[1] == int.class && p[2].isEnum() && p[4] == boolean.class) {
                    allChats = method;
                    allChats.setAccessible(true);
                }
            }
        }
        if (tab == null || allChats == null) {
            RuntimeLog.log("read all unavailable: " + (tab == null ? "tab" : "chat store") + " not found");
            return;
        }
        int hooks = 0;
        for (Method method : tab.getDeclaredMethods()) {
            if (method.getReturnType() != View.class || method.getParameterTypes().length != 1
                    || Modifier.isStatic(method.getModifiers())) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (param.getResult() instanceof View && INBOX_TAG.equals(Reflect.call(param.thisObject, "tag"))) {
                        attach((View) param.getResult());
                    }
                }
            });
            hooks++;
        }
        RuntimeLog.log("read all: tab hooks=" + hooks);
    }

    /** A long press on the Inbox tab opens the sheet; the tab's own tap is left as it is. */
    private static void attach(View tab) {
        tab.setOnLongClickListener(view -> {
            if (!Prefs.on(Prefs.READ_ALL)) return false;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (RUNNING.compareAndSet(false, true)) new Thread(() -> offer(view), "tpx-read-all").start();
            return true;
        });
    }

    /** The account's chats and TikTok's bulk action, looked up off the main thread. */
    private static final class Batch {
        Object facade;
        Method bulk;
        List<?> chats;
        int unread;
    }

    private static void offer(View tab) {
        Batch batch = null;
        try {
            batch = load();
        } catch (Throwable error) {
            RuntimeLog.log("read all failed: " + error.getClass().getSimpleName());
        } finally {
            RUNNING.set(false);
        }
        Batch ready = batch;
        MAIN.post(() -> show(tab, ready));
    }

    private static Batch load() throws ReflectiveOperationException {
        List<Object> social = StreakSentCheck.socialObjects(StreakApi.service(loader, CORE_SERVICE));
        Object dao = find(social, store::isInstance);
        if (dao == null) return null;
        Object all = null;
        for (Object constant : allChats.getParameterTypes()[2].getEnumConstants()) {
            if ("ALL".equals(((Enum<?>) constant).name())) all = constant;
        }
        List<?> chats = list(allChats.invoke(dao, -1, -1, all, null, false));
        if (chats == null) return null;
        Batch batch = new Batch();
        batch.chats = chats;
        for (Object chat : chats) {
            Object count = Reflect.call(chat, "getUnreadCount");
            if (count instanceof Number && ((Number) count).longValue() > 0) batch.unread++;
        }
        if (chats.isEmpty()) return batch;
        Class<?> chatType = chats.get(0).getClass();
        batch.facade = find(social, value -> bulk(value.getClass(), chatType) != null);
        batch.bulk = batch.facade == null ? null : bulk(batch.facade.getClass(), chatType);
        return batch;
    }

    /** TikTok-style sheet: "Read all" with the number of chats that have unread messages. */
    private static void show(View tab, Batch batch) {
        android.app.Activity activity = Views.activity(tab.getContext());
        if (activity == null || activity.isFinishing()) return;
        if (batch == null || (batch.unread > 0 && batch.bulk == null)) {
            toast(tab, I18n.get(I18n.S.READ_ALL_FAILED));
            return;
        }
        if (batch.unread == 0) {
            toast(tab, I18n.get(I18n.S.READ_ALL_NONE));
            return;
        }
        confirm(activity, batch.unread, () -> {
            if (RUNNING.compareAndSet(false, true)) new Thread(() -> mark(tab, batch), "tpx-read-all").start();
        });
    }

    /** A confirmation in the look of TikTok's own dialogs: title, text, then stacked buttons. */
    private static void confirm(android.app.Activity activity, int unread, Runnable yes) {
        float density = activity.getResources().getDisplayMetrics().density;
        boolean dark = (activity.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int surface = dark ? 0xFF2A2A2A : 0xFFFFFFFF;
        int primary = dark ? 0xFFFFFFFF : 0xFF161823;
        int secondary = dark ? 0xFFC2C2C6 : 0xFF5F6066;
        int divider = dark ? 0xFF3A3A3A : 0xFFE3E3E4;
        android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout box = new android.widget.LinearLayout(activity);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(surface);
        shape.setCornerRadius(14 * density);
        box.setBackground(shape);
        box.setClipToOutline(true);
        android.widget.TextView title = label(activity, I18n.get(I18n.S.READ_ALL_CONFIRM), 17, primary, Fonts.bold(activity));
        title.setPadding(dp(density, 20), dp(density, 24), dp(density, 20), dp(density, 6));
        box.addView(title);
        android.widget.TextView text = label(activity,
                String.format(java.util.Locale.ROOT, I18n.get(I18n.S.READ_ALL_CONFIRM_TEXT), unread), 14, secondary, Fonts.regular(activity));
        text.setPadding(dp(density, 20), 0, dp(density, 20), dp(density, 20));
        box.addView(text);
        // TikTok's red, which the accent color replaces when it is on.
        box.addView(button(activity, dialog, I18n.get(I18n.S.READ_ALL_ACTION), 0xFFFE2C55, divider, density, yes));
        box.addView(button(activity, dialog, activity.getString(android.R.string.cancel), primary, divider, density, null));
        dialog.setContentView(box);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(density, 56), dp(density, 320));
            window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private static android.widget.TextView label(android.content.Context context, String value, int size, int color,
            android.graphics.Typeface face) {
        android.widget.TextView view = new android.widget.TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setGravity(android.view.Gravity.CENTER);
        return view;
    }

    private static View button(android.app.Activity activity, android.app.Dialog dialog, String value, int color, int divider,
            float density, Runnable action) {
        android.widget.LinearLayout holder = new android.widget.LinearLayout(activity);
        holder.setOrientation(android.widget.LinearLayout.VERTICAL);
        View line = new View(activity);
        line.setBackgroundColor(divider);
        holder.addView(line, new android.widget.LinearLayout.LayoutParams(-1, Math.max(1, Math.round(density * 0.5f))));
        android.widget.TextView button = label(activity, value, 15, color, Fonts.semibold(activity));
        android.util.TypedValue ripple = new android.util.TypedValue();
        if (activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
            button.setBackgroundResource(ripple.resourceId);
        }
        button.setOnClickListener(view -> {
            dialog.dismiss();
            if (action != null) action.run();
        });
        holder.addView(button, new android.widget.LinearLayout.LayoutParams(-1, dp(density, 50)));
        return holder;
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }

    /** TikTok's own bulk read: local unread counts first, then the server, in batches of 100. */
    private static void mark(View tab, Batch batch) {
        boolean done = false;
        try {
            Class<?> callback = batch.bulk.getParameterTypes()[1];
            Object quiet = callback.isInterface() ? Proxy.newProxyInstance(loader, new Class<?>[]{callback},
                    (proxy, method, args) -> defaultValue(method.getReturnType())) : null;
            batch.bulk.invoke(batch.facade, batch.chats, quiet);
            done = true;
        } catch (Throwable error) {
            RuntimeLog.log("read all failed: " + error.getClass().getSimpleName());
        } finally {
            RUNNING.set(false);
        }
        toast(tab, I18n.get(done ? I18n.S.READ_ALL_DONE : I18n.S.READ_ALL_FAILED));
    }

    private static void toast(View tab, String text) {
        MAIN.post(() -> Toast.makeText(tab.getContext(), text, Toast.LENGTH_SHORT).show());
    }

    /** Classes already checked for the bulk method, with the answer. */
    private static final Map<Class<?>, Method> BULK = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Method NONE = InboxReadAll.class.getDeclaredMethods()[0];

    /**
     * The chat list's bulk "mark read" that takes chats, not chat ids. Both take (List, callback),
     * so the one meant is told apart by its code: it reads each chat's conversation id.
     */
    private static Method bulk(Class<?> type, Class<?> chatType) {
        Method known = BULK.get(type);
        if (known != null) return known == NONE ? null : known;
        List<Method> pairs = new java.util.ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (method.getReturnType() == void.class && p.length == 2 && p[0] == List.class
                    && !Modifier.isStatic(method.getModifiers()) && !p[1].isPrimitive()) pairs.add(method);
        }
        Method found = null;
        // A bulk facade has both the chats and the ids variant.
        if (pairs.size() >= 2) {
            String reads = chatType.getName() + "#getConversationId";
            for (Method method : pairs) {
                if (Discovery.invokedBy(type.getName(), method.getName()).contains(reads)) {
                    found = method;
                    found.setAccessible(true);
                    break;
                }
            }
        }
        BULK.put(type, found == null ? NONE : found);
        return found;
    }

    /** The first object reachable from the IM objects, a few references deep, that {@code wanted} accepts. */
    private static Object find(List<Object> roots, java.util.function.Predicate<Object> wanted) {
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (Object root : roots) queue.add(new Object[]{root, 0});
        while (!queue.isEmpty() && seen.size() < 4000) {
            Object[] entry = queue.poll();
            Object value = entry[0];
            int depth = (Integer) entry[1];
            if (value == null || seen.put(value, Boolean.TRUE) != null) continue;
            if (wanted.test(value)) return value;
            if (depth >= 6) continue;
            for (Class<?> owner = value.getClass(); owner != null && owner != Object.class; owner = owner.getSuperclass()) {
                String name = owner.getName();
                if (name.startsWith("java.") || name.startsWith("android.") || name.startsWith("kotlin.")) break;
                for (Field field : owner.getDeclaredFields()) {
                    Class<?> kind = field.getType();
                    if (Modifier.isStatic(field.getModifiers()) || kind.isPrimitive() || kind == String.class
                            || View.class.isAssignableFrom(kind)) continue;
                    try {
                        field.setAccessible(true);
                        Object next = field.get(value);
                        if (next != null) queue.add(new Object[]{next, depth + 1});
                    } catch (Throwable ignored) {
                        // Next field.
                    }
                }
            }
        }
        return null;
    }

    /** The query's result holds the chats in one List field (an error otherwise). */
    private static List<?> list(Object result) {
        if (result instanceof List) return (List<?>) result;
        if (result == null) return null;
        for (Field field : result.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(result);
                if (value instanceof List) return (List<?>) value;
            } catch (Throwable ignored) {
                // Next field.
            }
        }
        return null;
    }


    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
