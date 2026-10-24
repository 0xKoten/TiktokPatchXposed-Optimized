package com.golda.patchertiktok;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides "Read" from the people you chat with. TikTok marks a chat read on this phone first (badges,
 * unread counts), then sends the mark to its server; only the sending is skipped, and TikTok is told
 * it went through, so it does not retry later. Each chat left unmarked this way can be marked on
 * demand ("Mark as read" in a message's long-press menu), through TikTok's own sender that marks the
 * chat up to its latest message. Install after Discovery.init.
 */
final class ReadReceipts {
    /** Literal of the current sender (MarkConversationReadUseCase), in the method that sends. */
    private static final String SENT = "Mark conversation read request completed successfully";
    /** Literal of the older sender (MarkConversationReadHandler). */
    private static final String LEGACY = "skipMarkReadRequest: corrupted read_index detected, readIndex=";
    private static final String REQUEST = "com.bytedance.im.core.proto.Request";
    private static final String BODY = "com.bytedance.im.core.proto.MarkConversationReadRequestBody";

    /** Chats whose read mark was kept back, by conversation id. */
    private static final Map<String, Object> UNSENT = new ConcurrentHashMap<>();
    /** Requests TikTok just built, with the chat each was built from. */
    private static final ArrayDeque<Object[]> BUILT = new ArrayDeque<>();
    private static final ThreadLocal<Boolean> SENDING = new ThreadLocal<>();

    private static Constructor<?> success;
    private static Constructor<?> handlerConstructor;
    private static Method force;
    private static volatile Object core;

    private ReadReceipts() { }

    static void install(ClassLoader loader) {
        Class<?> request = Hooks.findClass(REQUEST, loader);
        Class<?> body = Hooks.findClass(BODY, loader);
        if (request == null || body == null) {
            RuntimeLog.log("read receipts unavailable: IM models not found");
            return;
        }
        int modern = installModern(loader, request);
        int legacy = installLegacy(loader, body);
        RuntimeLog.log("read receipts: current=" + modern + " older=" + legacy + " read now=" + (force != null));
    }

    /** MarkConversationReadUseCase: builds the request from the chat, then sends it in one method. */
    private static int installModern(ClassLoader loader, Class<?> request) {
        int hooks = 0;
        for (String hit : Discovery.methodsUsing(SENT)) {
            int split = hit.indexOf('#');
            Class<?> owner = split > 0 ? Hooks.findClass(hit.substring(0, split), loader) : null;
            if (owner == null) continue;
            String name = hit.substring(split + 1);
            Constructor<?> answer = result(loader, owner, name);
            if (answer == null) continue;
            success = answer;
            for (Method method : owner.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (method.getName().equals(name) && p.length == 2 && p[0] == request) {
                    Hooks.hook(method, SEND);
                    hooks++;
                } else if (method.getReturnType() == request && p.length == 3 && p[2] == boolean.class) {
                    // The request builder: remember which chat each request is for.
                    Hooks.hook(method, BUILD);
                }
            }
        }
        return hooks;
    }

    /** The send result: the class this method constructs from (boolean, String, int, Exception). */
    private static Constructor<?> result(ClassLoader loader, Class<?> owner, String method) {
        for (String call : Discovery.invokedBy(owner.getName(), method)) {
            int split = call.indexOf('#');
            if (split <= 0 || !call.endsWith("#<init>")) continue;
            Class<?> type = Hooks.findClass(call.substring(0, split), loader);
            if (type == null) continue;
            try {
                Constructor<?> constructor = type.getDeclaredConstructor(boolean.class, String.class, int.class, Exception.class);
                constructor.setAccessible(true);
                return constructor;
            } catch (NoSuchMethodException other) {
                // Another object built in that method.
            }
        }
        return null;
    }

    private static final Hooks.Hook BUILD = new Hooks.Hook() {
        @Override protected void after(Hooks.Call param) {
            if (!Prefs.on(Prefs.GHOST_READ) || param.getResult() == null) return;
            synchronized (BUILT) {
                BUILT.addFirst(new Object[]{param.getResult(), param.args[0]});
                while (BUILT.size() > 16) BUILT.removeLast();
            }
            if (core == null) core = coreOf(param.thisObject);
        }
    };

    /** The sending step: answered as sent, the chat remembered for "Mark as read". */
    private static final Hooks.Hook SEND = new Hooks.Hook() {
        @Override protected void before(Hooks.Call param) {
            if (!Prefs.on(Prefs.GHOST_READ) || SENDING.get() != null) return;
            Object chat = builtFrom(param.args[0]);
            String id = chat == null ? null : string(Reflect.call(chat, "getConversationId"));
            try {
                param.setResult(success.newInstance(true, "", -1014, null));
                // A request whose chat is unknown is still held back; it just cannot be sent later.
                if (id != null) UNSENT.put(id, chat);
            } catch (Throwable error) {
                // TikTok sends it as usual.
            }
        }
    };

    /** MarkConversationReadHandler: its typed sender, and the forced sender behind "Mark as read". */
    private static int installLegacy(ClassLoader loader, Class<?> body) {
        Class<?> owner = Discovery.firstClass(loader, LEGACY);
        if (owner == null) return 0;
        int hooks = 0;
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) continue;
            if (p.length == 4 && p[0] == int.class && p[1] == body && p[2] == String.class && p[3] == int.class) {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (Prefs.on(Prefs.GHOST_READ) && SENDING.get() == null) param.setResult(null);
                    }
                });
                hooks++;
            } else if (p.length == 2 && p[1] == String.class && !p[0].isPrimitive()) {
                force = method;
                force.setAccessible(true);
            }
        }
        Constructor<?>[] constructors = owner.getDeclaredConstructors();
        if (constructors.length == 1 && constructors[0].getParameterTypes().length == 1) {
            handlerConstructor = constructors[0];
            handlerConstructor.setAccessible(true);
        }
        return hooks;
    }

    /** True when this chat's read mark was kept back and can be sent now. */
    static boolean unsent(String conversation) {
        return conversation != null && UNSENT.containsKey(conversation) && force != null && handlerConstructor != null && core != null;
    }

    /** Sends the chat's read mark now, up to its latest message, through TikTok's own sender. */
    static void markRead(String conversation) {
        Object chat = UNSENT.get(conversation);
        if (chat == null || force == null || handlerConstructor == null || core == null) return;
        SENDING.set(Boolean.TRUE);
        try {
            Object sender = handlerConstructor.newInstance(core);
            force.invoke(sender, chat, conversation);
            UNSENT.remove(conversation);
        } catch (Throwable error) {
            RuntimeLog.log("mark as read failed: " + error.getClass().getSimpleName());
        } finally {
            SENDING.remove();
        }
    }

    private static Object builtFrom(Object request) {
        synchronized (BUILT) {
            for (Iterator<Object[]> it = BUILT.iterator(); it.hasNext(); ) {
                Object[] pair = it.next();
                if (pair[0] == request) return pair[1];
            }
        }
        return null;
    }

    /** The IM core the older sender is built with: the use case's field of that same type. */
    private static Object coreOf(Object useCase) {
        if (handlerConstructor == null) return null;
        Class<?> type = handlerConstructor.getParameterTypes()[0];
        for (Field field : useCase.getClass().getDeclaredFields()) {
            if (!type.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                return field.get(useCase);
            } catch (Throwable ignored) {
                // Next field.
            }
        }
        return null;
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
