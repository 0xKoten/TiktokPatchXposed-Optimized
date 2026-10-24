package com.golda.patchertiktok;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Names and avatars of streak partners, from what TikTok already has on the phone: its in-memory
 * user cache first, then its contacts database. Neither asks the server.
 */
final class StreakPeople {
    private static final String CONTACTS = "com.ss.android.ugc.aweme.im.contacts.api.IMContactApi";
    private static final String USER = "com.ss.android.ugc.aweme.im.contacts.api.model.IMUser";

    static final class Person {
        final String name;
        final String handle;
        final String avatar;

        Person(String name, String handle, String avatar) {
            this.name = name;
            this.handle = handle;
            this.avatar = avatar;
        }
    }

    private StreakPeople() { }

    /** By peer uid; people TikTok does not know locally are missing. Off the main thread. */
    static Map<String, Person> lookup(List<AutoStreakManager.Partner> partners) {
        Map<String, Person> result = new HashMap<>();
        ClassLoader loader = AutoStreakManager.loader();
        if (loader == null) return result;
        try {
            Object contacts = StreakApi.service(loader, CONTACTS);
            Class<?> user = Class.forName(USER, false, loader);
            // The user repository: its only (uid, secUid) -> IMUser reads memory alone.
            Object[] memory = reader(contacts, user, String.class, String.class);
            // The contacts store: its only (uid, scene, fetch) -> IMUser reads the database; fetch stays off.
            Object[] database = reader(contacts, user, String.class, String.class, boolean.class);
            for (AutoStreakManager.Partner partner : partners) {
                Object found = null;
                try {
                    if (memory != null) found = ((Method) memory[1]).invoke(memory[0], partner.peer, null);
                    if (found == null && database != null) {
                        found = ((Method) database[1]).invoke(database[0], partner.peer, "streak_settings", false);
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    found = null;
                }
                if (found != null) result.put(partner.peer, person(found));
            }
        } catch (Throwable error) {
            RuntimeLog.log("streak names unavailable: " + error.getClass().getSimpleName());
        }
        return result;
    }

    /** {owner, method}: a getter on the service whose result has exactly one method of this shape returning IMUser. */
    private static Object[] reader(Object service, Class<?> user, Class<?>... shape) throws ReflectiveOperationException {
        for (Method getter : service.getClass().getMethods()) {
            if (getter.getParameterCount() != 0 || getter.getReturnType().isPrimitive()
                    || Modifier.isStatic(getter.getModifiers()) || getter.getDeclaringClass() == Object.class) continue;
            Method only = null;
            int count = 0;
            for (Method method : getter.getReturnType().getMethods()) {
                if (method.getReturnType() == user && java.util.Arrays.equals(method.getParameterTypes(), shape)) {
                    only = method;
                    count++;
                }
            }
            if (count != 1) continue;
            Object owner = getter.invoke(service);
            if (owner == null) continue;
            only.setAccessible(true);
            return new Object[]{owner, only};
        }
        return null;
    }

    private static Person person(Object user) {
        String name = text(Reflect.call(user, "getNickName"));
        String handle = text(Reflect.call(user, "getUniqueId"));
        String avatar = null;
        for (String getter : new String[]{"getAvatarThumb", "getAvatarMedium"}) {
            Object model = Reflect.call(user, getter);
            Object urls = model == null ? null : Reflect.call(model, "getUrlList");
            if (urls instanceof List<?>) {
                for (Object url : (List<?>) urls) {
                    // Blocked users get a res:// placeholder; only real addresses are loaded.
                    if (url instanceof String && ((String) url).startsWith("http")) {
                        avatar = (String) url;
                        break;
                    }
                }
            }
            if (avatar != null) break;
        }
        return new Person(name, handle, avatar);
    }

    private static String text(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : null;
    }
}
