package com.golda.patchertiktok;

import android.os.SystemClock;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * "Did this account already send anything in this chat since the streak day began?", answered
 * from TikTok's local message history. That history also holds what the user sent from another
 * phone once TikTok has pulled new messages, so the answer is only trusted after such a pull
 * since TikTok came to the foreground. TikTok asks the same question itself before it shows its
 * own streak reminder; the query is found through that code.
 */
final class StreakSentCheck {
    enum Answer { SENT, NOT_SENT, UNKNOWN }

    private static final String CORE_SERVICE = "com.bytedance.ies.im.core.api.proxy.IIMCoreProxyService";
    private static final String SOCIAL_IM = "TIKTOK_SOCIAL_IM";
    /** toString of TikTok's "message pull finished" event; its boolean is the success flag. */
    private static final String PULL_DONE = "PullCompleted(inbox=";
    /** Log text in TikTok's own "has this user sent anything since the boundary" check. */
    private static final String SENT_FINGERPRINT = "InlineMsgHelper: tryInsertRemindInlineMsgForGroupChat, has sent message already";

    private static volatile boolean watching;
    private static volatile long lastPull = -1;
    /** Methods TikTok's own sent check calls, read from its code at startup while the index is open. */
    private static volatile List<String> wanted = new ArrayList<>();

    private Object operator;
    private Method query;

    /** Watches TikTok's pull results. Install after Discovery.init, before TikTok's IM starts. */
    static void capture(ClassLoader loader) {
        int hooks = 0;
        for (String name : Discovery.classesUsing(PULL_DONE)) {
            Class<?> event = Hooks.findClass(name, loader);
            if (event == null) continue;
            hooks += Hooks.hookConstructors(event, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    for (Object arg : param.args) {
                        if (arg instanceof Boolean) {
                            if ((Boolean) arg) lastPull = SystemClock.elapsedRealtime();
                            return;
                        }
                    }
                }
            });
        }
        watching = hooks > 0;
        wanted = queriesUsedBySentCheck();
        RuntimeLog.log("streak sync watch: " + hooks + ", sent query names " + wanted.size());
    }

    /** True when TikTok finished a successful message pull at or after {@code elapsed}, or pulls cannot be watched. */
    static boolean pulledSince(long elapsed) {
        return !watching || lastPull >= elapsed;
    }

    StreakSentCheck(ClassLoader loader) throws ReflectiveOperationException {
        Object service = StreakApi.service(loader, CORE_SERVICE);
        // Several service methods take the business; the IM client is the one with the message store.
        for (Object client : socialObjects(service)) {
            for (Method getter : client.getClass().getMethods()) {
                if (getter.getParameterTypes().length != 0 || Modifier.isStatic(getter.getModifiers())) continue;
                Method candidate = senderSince(getter.getReturnType(), wanted);
                if (candidate == null) continue;
                getter.setAccessible(true);
                Object value = getter.invoke(client);
                if (value == null) continue;
                candidate.setAccessible(true);
                operator = value;
                query = candidate;
                return;
            }
        }
        throw new NoSuchMethodException("Sent-since query");
    }

    /** What the service hands out for TikTok's social inbox (TIKTOK_SOCIAL_IM), one per method that takes it. */
    static List<Object> socialObjects(Object service) throws ReflectiveOperationException {
        List<Object> found = new ArrayList<>();
        for (Method method : service.getClass().getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 1 || !p[0].isEnum() || method.getReturnType().isPrimitive()) continue;
            for (Object constant : p[0].getEnumConstants()) {
                if (!SOCIAL_IM.equals(((Enum<?>) constant).name())) continue;
                method.setAccessible(true);
                Object value = method.invoke(service, constant);
                if (value != null) found.add(value);
            }
        }
        return found;
    }

    /** Names of the methods TikTok's own sent check calls; they include the query we want. */
    private static List<String> queriesUsedBySentCheck() {
        List<String> names = new ArrayList<>();
        for (String hit : Discovery.methodsUsing(SENT_FINGERPRINT)) {
            int split = hit.indexOf('#');
            if (split <= 0) continue;
            for (String call : Discovery.invokedBy(hit.substring(0, split), hit.substring(split + 1))) {
                int at = call.indexOf('#');
                if (at > 0) names.add(call.substring(at + 1));
            }
        }
        return names;
    }

    /** (conversation, after-millis, sender) -> List on {@code type}, preferring the one TikTok's check calls. */
    private static Method senderSince(Class<?> type, List<String> wanted) {
        List<Method> shaped = new ArrayList<>();
        for (Method method : type.getMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (p.length == 3 && p[0] == String.class && p[1] == long.class && p[2] == String.class
                    && List.class.isAssignableFrom(method.getReturnType())) shaped.add(method);
        }
        for (Method method : shaped) if (wanted.contains(method.getName())) return method;
        return shaped.size() == 1 && wanted.isEmpty() ? shaped.get(0) : null;
    }

    /**
     * Run on a worker: TikTok reads its database inline. {@code activeBefore} is the streak record's
     * boundary (seconds or milliseconds) where the current at-risk day begins.
     */
    Answer check(String account, String conversation, long activeBefore) {
        long since = StreakKeys.seconds(activeBefore) * 1000L - 1;
        Object rows;
        try {
            rows = query.invoke(operator, conversation, since, account);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return Answer.UNKNOWN;
        }
        if (!(rows instanceof List<?>)) return Answer.UNKNOWN;
        for (Object message : (List<?>) rows) {
            if (message == null) continue;
            Object ok = Reflect.call(message, "isSuccessStatus");
            Object id = Reflect.call(message, "getMsgId");
            // A server id tells a real message from TikTok's own local notices.
            if (Boolean.TRUE.equals(ok) && id instanceof Number && ((Number) id).longValue() > 0) return Answer.SENT;
        }
        return Answer.NOT_SENT;
    }
}
