package com.golda.patchertiktok;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Shows the creator's country as a flag after the post date ("· 5 d 🇩🇪"): the author's region,
 * or the video's when the author has none. The flag is set when the video on screen renders its
 * first frame and stays pinned: if TikTok rewrites the date, the flag comes back. On a profile, the account name gets the flag of that user's region while the name
 * shown is that user's.
 */
final class AuthorRegion {
    private static final String PANEL = "com.ss.android.ugc.aweme.feed.panel.BaseListFragmentPanel";
    private static final String DATE_LABEL = "tv_post_time";
    private static final String USER = "com.ss.android.ugc.aweme.profile.model.User";
    private static final String PROFILE_MODEL = "com.ss.android.ugc.profile.platform.base.viewmodel.ProfilePlatformViewModel";
    private static final String ACCOUNT_NAME = "com.ss.android.ugc.profile.platform.business.header.business.info.business.userinfo.base.AccountNameInfoComponent";
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());

    private static java.lang.ref.WeakReference<Object> profileUser = new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<TextView> accountName = new java.lang.ref.WeakReference<>(null);

    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";
    private static final String FLAG = "com.golda.patchertiktok.flag";
    private static final String OWNER = "com.golda.patchertiktok.flagOwner";
    private static final String WATCHED = "com.golda.patchertiktok.flagWatched";
    private static final String WRITTEN = "com.golda.patchertiktok.flagWritten";
    private static Method currentAweme;
    private static Method pager;
    private static int dateId;

    private AuthorRegion() { }

    static void install(ClassLoader loader) {
        Class<?> panel = Hooks.findClass(PANEL, loader);
        if (panel == null || (currentAweme = RenderedAdSkip.currentAweme(panel)) == null) {
            RuntimeLog.log("author region unavailable: feed panel not found");
            return;
        }
        currentAweme.setAccessible(true);
        for (Method method : panel.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && PAGER.equals(method.getReturnType().getName())) {
                method.setAccessible(true);
                pager = method;
            }
        }
        int hooks = 0;
        for (Method method : panel.getDeclaredMethods()) {
            if (!"onRenderFirstFrame".equals(method.getName()) || method.getReturnType() != void.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (Prefs.on(Prefs.AUTHOR_REGION)) show(param.thisObject);
                }
            });
            hooks++;
        }
        installProfile(loader);
    }

    private static int id(View view) {
        if (dateId == 0) {
            dateId = view.getResources().getIdentifier(DATE_LABEL, "id", view.getContext().getPackageName());
            if (dateId == 0) dateId = -1;
        }
        return dateId;
    }

    /**
     * Puts {@code flag} at the end of the label. TikTok reuses labels for other videos and users, so
     * when it writes new text the flag is dropped, unless {@code owner} (a user's name) is still in
     * it: then the flag belongs to that text and comes back. A null flag removes ours.
     */
    private static void pin(TextView label, String flag, String owner) {
        Hooks.setExtra(label, FLAG, flag);
        Hooks.setExtra(label, OWNER, owner);
        if (Hooks.getExtra(label, WATCHED) == null) {
            Hooks.setExtra(label, WATCHED, Boolean.TRUE);
            label.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }

                @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }

                @Override public void afterTextChanged(android.text.Editable text) {
                    // Our own write is not news.
                    if (label.getText().toString().equals(Hooks.getExtra(label, WRITTEN))) return;
                    if (Hooks.getExtra(label, OWNER) == null) Hooks.setExtra(label, FLAG, null);
                    label.post(() -> refresh(label));
                }
            });
        }
        refresh(label);
    }

    private static void refresh(TextView label) {
        Object flag = Hooks.getExtra(label, FLAG);
        Object owner = Hooks.getExtra(label, OWNER);
        CharSequence text = label.getText();
        if (text == null) return;
        String shown = text.toString();
        String plain = RegionFlag.without(shown);
        boolean wanted = flag instanceof String && Prefs.on(Prefs.AUTHOR_REGION) && !plain.isEmpty()
                && (owner == null || plain.contains((String) owner));
        String result = wanted ? plain + " " + flag : plain;
        if (result.equals(shown)) return;
        Hooks.setExtra(label, WRITTEN, result);
        label.setText(result);
    }

    private static void installProfile(ClassLoader loader) {
        Class<?> user = Hooks.findClass(USER, loader);
        Class<?> model = Hooks.findClass(PROFILE_MODEL, loader);
        Class<?> component = Hooks.findClass(ACCOUNT_NAME, loader);
        Method label = null;
        if (component != null) {
            for (Method method : component.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && TextView.class.isAssignableFrom(method.getReturnType())) {
                    if (label != null) {
                        label = null;
                        break;
                    }
                    label = method;
                }
            }
        }
        if (user == null || model == null || label == null) {
            RuntimeLog.log("author region on profiles unavailable: profile header not recognised");
            return;
        }
        label.setAccessible(true);
        Method nameView = label;
        // The profile's user arrives through the view model; any method handing it a User counts.
        for (Method method : model.getDeclaredMethods()) {
            int index = -1;
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) if (types[i] == user) index = i;
            if (index < 0 || method.getReturnType() != void.class) continue;
            int position = index;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    Object value = param.args[position];
                    if (value == null || !Prefs.on(Prefs.AUTHOR_REGION)) return;
                    profileUser = new java.lang.ref.WeakReference<>(value);
                    // The header can bind before or after the user arrives; look again shortly.
                    MAIN.post(AuthorRegion::markProfile);
                    MAIN.postDelayed(AuthorRegion::markProfile, 400);
                    MAIN.postDelayed(AuthorRegion::markProfile, 1_200);
                }
            });
        }
        // The name line binds after the data; mark it whenever the component refreshes.
        for (Method method : component.getDeclaredMethods()) {
            if (method.getReturnType() != void.class || Modifier.isStatic(method.getModifiers())) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (!Prefs.on(Prefs.AUTHOR_REGION)) return;
                    try {
                        Object view = nameView.invoke(param.thisObject);
                        if (view instanceof TextView) accountName = new java.lang.ref.WeakReference<>((TextView) view);
                    } catch (Throwable ignored) {
                        return;
                    }
                    markProfile();
                    MAIN.postDelayed(AuthorRegion::markProfile, 300);
                }
            });
        }
    }

    /** Adds the flag when the account name on screen belongs to the profile's user. */
    private static void markProfile() {
        TextView name = accountName.get();
        Object user = profileUser.get();
        if (name == null || user == null) return;
        Object handle = Reflect.call(user, "getUniqueId");
        Object nickname = Reflect.call(user, "getNickname");
        String flag = RegionFlag.of(userRegion(user));
        CharSequence text = name.getText();
        if (flag == null || text == null || text.length() == 0) return;
        String shown = text.toString();
        String owner = null;
        if (handle instanceof String && !((String) handle).isEmpty() && shown.contains((String) handle)) owner = (String) handle;
        else if (nickname instanceof String && !((String) nickname).isEmpty() && shown.contains((String) nickname)) owner = (String) nickname;
        if (owner != null) pin(name, flag, owner);
    }

    private static String userRegion(Object user) {
        for (String getter : new String[]{"getRegion", "getIsoCountryCode", "getCountry"}) {
            Object value = Reflect.call(user, getter);
            if (value instanceof String && ((String) value).length() == 2) return (String) value;
        }
        for (String field : new String[]{"region", "isoCountryCode", "country"}) {
            Object value = Reflect.get(user, field);
            if (value instanceof String && ((String) value).length() == 2) return (String) value;
        }
        return null;
    }

    private static void show(Object panel) {
        Object activity = Reflect.call(panel, "getActivity");
        if (!(activity instanceof Activity) || pager == null) return;
        View root = ((Activity) activity).getWindow().getDecorView();
        if (id(root) < 0) return;
        Runnable mark = () -> {
            // Read the video again when drawing: the pager may have moved on since the event.
            Object aweme;
            Object feed;
            try {
                aweme = currentAweme.invoke(panel);
                feed = pager.invoke(panel);
            } catch (Throwable error) {
                return;
            }
            String flag = RegionFlag.of(region(aweme));
            // Only the feed's own pages: comments use the same date label id.
            if (flag == null || !(feed instanceof View)) return;
            TextView label = visibleLabel((View) feed);
            if (label != null) pin(label, flag, null);
        };
        // Now if the label is already bound, and once more after the pending layout pass.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) mark.run();
        root.post(mark);
    }

    private static String region(Object aweme) {
        Object author = Reflect.call(aweme, "getAuthor");
        Object region = Reflect.call(author, "getRegion");
        if (!(region instanceof String) || ((String) region).length() != 2) region = Reflect.call(aweme, "getRegion");
        return region instanceof String ? (String) region : null;
    }

    /** The date label of the item that is fully on screen; others in the pager are off screen. */
    private static TextView visibleLabel(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view.getId() == dateId && view instanceof TextView) {
            Rect rect = new Rect();
            return view.getGlobalVisibleRect(rect) && rect.height() > 0 ? (TextView) view : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = visibleLabel(group.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }
}
