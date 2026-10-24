package com.golda.patchertiktok;

import android.app.Activity;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;

/**
 * Saves a profile photo. Tapping someone's avatar opens TikTok's enlarged photo screen; holding the
 * photo there saves the largest size TikTok supplies (larger, then medium, then thumb), not the
 * scaled circle on screen. A tap still closes the screen as before.
 */
final class AvatarDownload {
    private static final String SCREEN = "com.ss.android.ugc.profile.business.ur.enlarge.EnlargeAvatarActivity";
    private static final String USER = "com.ss.android.ugc.aweme.profile.model.User";
    private static final String AVATAR_VIEW = "com.ss.android.ugc.aweme.avatar.AvatarComponentView";

    private AvatarDownload() { }

    static void install(ClassLoader loader) {
        Class<?> screen = Hooks.findClass(SCREEN, loader);
        Class<?> user = Hooks.findClass(USER, loader);
        if (screen == null || user == null) {
            RuntimeLog.log("avatar download unavailable: enlarged photo screen not found");
            return;
        }
        Class<?> avatarView = Hooks.findClass(AVATAR_VIEW, loader);
        Hooks.findAndHook(screen, "onCreate", Bundle.class, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.AVATAR_DOWNLOAD)) return;
                Activity activity = (Activity) param.thisObject;
                // The photo view is bound once the screen is laid out.
                activity.getWindow().getDecorView().post(() -> attach(activity, user, avatarView));
            }
        });
    }

    private static void attach(Activity activity, Class<?> user, Class<?> avatarView) {
        Object owner = fieldOf(activity, user);
        if (owner == null) owner = activity.getIntent().getSerializableExtra("user_key");
        View photo = avatarView == null ? null : (View) fieldOf(activity, avatarView);
        if (owner == null || photo == null) return;
        Object person = owner;
        View.OnLongClickListener save = view -> {
            DownloadModels.Item item = null;
            for (String getter : new String[]{"getAvatarLarger", "getAvatarMedium", "getAvatarThumb"}) {
                item = DownloadModels.picture(Reflect.call(person, getter));
                if (item != null) break;
            }
            if (item == null) return false;
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            Object id = Reflect.call(person, "getUid");
            DownloadsPlus.save(activity, "avatar_" + id, Collections.singletonList(item));
            return true;
        };
        // The picture inside the avatar view takes the touches, so every level of it listens.
        listen(photo, save, 0);
    }

    private static void listen(View view, View.OnLongClickListener save, int depth) {
        view.setOnLongClickListener(save);
        if (depth >= 3 || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) listen(group.getChildAt(i), save, depth + 1);
    }

    /** The single instance field of {@code type} on the screen; null when there is none or several. */
    private static Object fieldOf(Object target, Class<?> type) {
        Object found = null;
        for (Class<?> owner = target.getClass(); owner != null && owner != Activity.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !type.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value == null) continue;
                    if (found != null && found != value) return null;
                    found = value;
                } catch (Throwable ignored) {
                    // Next field.
                }
            }
        }
        return found;
    }
}
