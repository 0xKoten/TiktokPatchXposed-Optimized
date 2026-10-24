package com.golda.patchertiktok;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Hosts module settings, adds the native row in "Settings and privacy",
 * and adds an entrance row directly in the top-right hamburger menu (☰)
 * right below "Settings and privacy".
 */
final class SettingsEntry {
    static final String HOST_ACTIVITY = "com.ss.android.ugc.aweme.setting.ui.SettingContainerActivity";
    static final String OPEN_EXTRA = "com.golda.patchertiktok.OPEN_SETTINGS";
    private static final String KEY = "tiktokpatchxposed_settings";
    private static final String KEY_MENU_ROW = "tiktokpatchxposed_menu_row";
    private static final String FRAGMENT = "com.ss.android.ugc.aweme.setting.ui.rvmpcompose.SettingsComposeRvmpFragment";
    private static final String PRIVACY_GROUP = "com.ss.android.ugc.aweme.setting.ui.rvmpcompose.group.privacy.PrivacyGroupVM";
    private static final String PRIVACY_ITEM = "com.ss.android.ugc.aweme.setting.ui.rvmpcompose.group.privacy.cell.PrivacyItemVM";

    private static volatile Application application;
    private static volatile Object item;
    private static volatile Object state;
    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private static volatile long lastOpen;
    private static volatile boolean openPending;

    private SettingsEntry() { }

    /** Swaps in {@link ModSettingsActivity} when TikTok's settings activity is started with our extra. */
    static void installHost() {
        Hooks.hookAll(Instrumentation.class, "newActivity", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (param.args.length != 3 || !(param.args[2] instanceof Intent)) return;
                Intent intent = (Intent) param.args[2];
                if (HOST_ACTIVITY.equals(param.args[1]) && intent.getBooleanExtra(ModSettingsActivity.EXTRA, false)) {
                    param.setResult(new ModSettingsActivity());
                } else if (AppRestart.HOST.equals(param.args[1]) && intent.getBooleanExtra(AppRestart.EXTRA, false)) {
                    param.setResult(new AppRestart.Trampoline());
                }
            }
        });
    }

    static void install(Application app, ClassLoader loader) {
        application = app;
        trackActivities(app);
        try {
            installHamburgerMenu(app, loader);
        } catch (Throwable error) {
            RuntimeLog.log("hamburger menu hook error: " + error);
        }
        try {
            injectRow(loader);
        } catch (Throwable error) {
            RuntimeLog.log("settings row unavailable: " + error);
        }
    }

    static void open(Context context) {
        long now = SystemClock.uptimeMillis();
        if (now - lastOpen < 600) return;
        lastOpen = now;
        Context host = Views.activity(context);
        if (host == null) host = resumed.get();
        if (host == null) host = context;
        if (host == null) return;
        boolean dark = (host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        host.startActivity(ModSettingsActivity.intent(host, dark));
    }

    // ---- Hamburger Menu (☰) Injection ----------------------------------------------------

    static boolean isPrivacyName(String s) {
        if (s == null) return false;
        String lower = s.trim().toLowerCase();
        if (lower.equals("隱私設定") || lower.equals("隐私设置") || lower.equals("privacy settings")
                || lower.equals("privacy") || lower.equals("設定與隱私") || lower.equals("设置与隐私")
                || lower.equals("settings and privacy") || lower.equals("settings & privacy")
                || lower.equals("settings") || lower.equals("設定") || lower.equals("设置")) {
            return true;
        }
        if ((lower.contains("隱私") || lower.contains("隐私") || lower.contains("privacy"))
                && !lower.contains("政策") && !lower.contains("條款") && !lower.contains("policy") && !lower.contains("terms")) {
            return true;
        }
        if (lower.contains("設定與") || lower.contains("设置与") || lower.contains("settings and")) {
            return true;
        }
        return false;
    }

        private static void installHamburgerMenu(Application app, ClassLoader loader) {
        // Hook 1: TextView.setText(CharSequence, BufferType) and setText(CharSequence)
        Hooks.Hook textHook = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof CharSequence)) return;
                CharSequence cs = (CharSequence) param.args[0];
                if (cs == null) return;
                String text = cs.toString().trim();
                if (isPrivacyName(text)) {
                    TextView tv = (TextView) param.thisObject;
                    tv.post(() -> tryInjectUnderPrivacy(tv));
                }
            }
        };

        try {
            Hooks.findAndHook(TextView.class, "setText", CharSequence.class, TextView.BufferType.class, textHook);
        } catch (Throwable t) {
            RuntimeLog.log("TextView.setText(2) hook error: " + t);
        }

        try {
            Hooks.findAndHook(TextView.class, "setText", CharSequence.class, textHook);
        } catch (Throwable t) {
            RuntimeLog.log("TextView.setText(1) hook error: " + t);
        }

        // Hook 2: View.onAttachedToWindow
        try {
            Hooks.findAndHook(View.class, "onAttachedToWindow", new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    View v = (View) param.thisObject;
                    if (v instanceof TextView) {
                        TextView tv = (TextView) v;
                        CharSequence cs = tv.getText();
                        if (cs != null && isPrivacyName(cs.toString().trim())) {
                            tv.post(() -> tryInjectUnderPrivacy(tv));
                        }
                    }
                }
            });
        } catch (Throwable t) {
            RuntimeLog.log("View.onAttachedToWindow hook error: " + t);
        }

        // Hook 3: SideBarRowItemAssem (ByteDance PowerCell for drawer items)
        try {
            Class<?> assemClass = Hooks.findClass("com.ss.android.ugc.aweme.sidebar.components.list.SideBarRowItemAssem", loader);
            if (assemClass != null) {
                Hooks.hookAll(assemClass, "j4", new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        try {
                            Object assem = param.thisObject;
                            Method getContentView = assem.getClass().getMethod("getContentView");
                            View cellView = (View) getContentView.invoke(assem);
                            if (cellView != null) {
                                cellView.post(() -> scanAndInjectPrivacy(cellView));
                            }
                        } catch (Throwable ignored) { }
                    }
                });
            }
        } catch (Throwable t) {
            RuntimeLog.log("SideBarRowItemAssem hook error: " + t);
        }

        // Hook 4: ProfileSidebarPageFragment
        try {
            Class<?> psfClass = Hooks.findClass("com.ss.android.ugc.aweme.sidebar.profile.ProfileSidebarPageFragment", loader);
            if (psfClass != null) {
                Hooks.hookAll(psfClass, "onViewCreated", new Hooks.Hook() {
                    @Override protected void after(Hooks.Call param) {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof View) {
                            View root = (View) param.args[0];
                            root.postDelayed(() -> scanAndInjectPrivacy(root), 200);
                            root.postDelayed(() -> scanAndInjectPrivacy(root), 600);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            RuntimeLog.log("ProfileSidebarPageFragment hook error: " + t);
        }

        // Hook 5: Dialog.show()
        try {
            Hooks.hookAll(android.app.Dialog.class, "show", new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    android.app.Dialog dialog = (android.app.Dialog) param.thisObject;
                    if (dialog.getWindow() != null && dialog.getWindow().getDecorView() != null) {
                        View decor = dialog.getWindow().getDecorView();
                        decor.postDelayed(() -> scanAndInjectPrivacy(decor), 150);
                        decor.postDelayed(() -> scanAndInjectPrivacy(decor), 500);
                    }
                }
            });
        } catch (Throwable t) {
            RuntimeLog.log("Dialog.show hook error: " + t);
        }
    }

    private static void scanAndInjectPrivacy(View root) {
        if (root == null) return;
        View privacyView = findSettingsView(root);
        if (privacyView instanceof TextView) {
            tryInjectUnderPrivacy((TextView) privacyView);
        } else if (privacyView != null) {
            attachToContainer(privacyView);
        }
    }

    private static synchronized void tryInjectUnderPrivacy(TextView tv) {
        if (tv == null) return;

        // Never inject inside the sub-settings container itself (only inside hamburger menu / drawer / sheets)
        Activity act = Views.activity(tv.getContext());
        if (act != null) {
            String actName = act.getClass().getName();
            if (actName.contains("ModSettingsActivity") || actName.contains("SettingContainerActivity")) {
                return;
            }
        }

        // Trace up to the item row (direct child of list container or cell)
        View itemRow = tv;
        ViewGroup container = null;
        View curr = tv;
        while (curr.getParent() instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) curr.getParent();
            String pName = p.getClass().getName();

            // RecyclerView, PowerList, or ListView
            if (pName.contains("RecyclerView") || pName.contains("PowerList") || pName.contains("ListView")) {
                container = p;
                itemRow = curr;
                break;
            }

            // Vertical LinearLayout (e.g. section in card/drawer)
            if (p instanceof LinearLayout && ((LinearLayout) p).getOrientation() == LinearLayout.VERTICAL) {
                container = p;
                itemRow = curr;
                if (p.getChildCount() >= 2) {
                    break;
                }
            }

            itemRow = curr;
            curr = p;
        }

        if (itemRow == null) itemRow = tv;

        if (itemRow.findViewWithTag(KEY_MENU_ROW) != null) return;
        if (container != null && container.findViewWithTag(KEY_MENU_ROW) != null) return;
        if (itemRow.getParent() instanceof ViewGroup && ((ViewGroup) itemRow.getParent()).findViewWithTag(KEY_MENU_ROW) != null) return;

        final View targetRow = itemRow;
        Context ctx = targetRow.getContext();
        View ourRow = createMenuRow(ctx, targetRow, () -> dismissDrawer(targetRow));
        ourRow.setTag(KEY_MENU_ROW);

        if (container instanceof LinearLayout && ((LinearLayout) container).getOrientation() == LinearLayout.VERTICAL) {
            int index = container.indexOfChild(targetRow);
            if (index >= 0) {
                container.addView(ourRow, index + 1);
                RuntimeLog.log("Injected menu row directly under privacy item at index " + (index + 1));
                return;
            }
        }

        // Container is RecyclerView or item row: append ourRow directly below the privacy row
        if (targetRow instanceof ViewGroup) {
            ViewGroup rowGroup = (ViewGroup) targetRow;
            ViewGroup.LayoutParams rlp = rowGroup.getLayoutParams();
            int origHeight = rowGroup.getHeight();
            if (origHeight <= 0 && rlp != null && rlp.height > 0) origHeight = rlp.height;
            if (origHeight <= 0) origHeight = dp(ctx, 48);

            if (rlp != null) {
                rlp.height = origHeight + dp(ctx, 48);
                rowGroup.setLayoutParams(rlp);
            }

            if (rowGroup instanceof FrameLayout) {
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 48));
                flp.topMargin = origHeight;
                rowGroup.addView(ourRow, flp);
                RuntimeLog.log("Injected into row FrameLayout with topMargin " + origHeight);
            } else {
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowGroup.addView(ourRow, lp);
                RuntimeLog.log("Injected into rowGroup " + rowGroup.getClass().getSimpleName());
            }
        } else if (targetRow.getParent() instanceof ViewGroup) {
            ViewGroup parentGroup = (ViewGroup) targetRow.getParent();
            int idx = parentGroup.indexOfChild(targetRow);
            parentGroup.addView(ourRow, idx + 1);
            RuntimeLog.log("Injected into targetRow.getParent() at " + (idx + 1));
        }
    }

private static void attachToContainer(View root) {
        if (root == null) return;
        if (root.findViewWithTag(KEY_MENU_ROW) != null) return;

        View settingsView = findSettingsView(root);
        if (settingsView == null) return;
        if (settingsView instanceof TextView) {
            tryInjectUnderPrivacy((TextView) settingsView);
            return;
        }

        ViewGroup container = null;
        View rowView = settingsView;
        while (rowView.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) rowView.getParent();
            if (parent == root || parent.getParent() == root || parent instanceof LinearLayout) {
                container = parent;
                break;
            }
            rowView = (View) rowView.getParent();
        }

        if (container == null && root instanceof ViewGroup) {
            container = (ViewGroup) root;
        }

        if (container != null && container.findViewWithTag(KEY_MENU_ROW) == null) {
            int insertIndex = container.indexOfChild(rowView) + 1;
            View ourRow = createMenuRow(container.getContext(), settingsView, () -> dismissDrawer(root));
            ourRow.setTag(KEY_MENU_ROW);
            if (insertIndex > 0 && insertIndex <= container.getChildCount()) {
                container.addView(ourRow, insertIndex);
            } else {
                container.addView(ourRow);
            }
            RuntimeLog.log("hamburger menu row inserted at " + insertIndex);
        }
    }

    private static View findSettingsView(View view) {
        if (view == null) return null;
        if (KEY_MENU_ROW.equals(view.getTag())) return null;
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null && isPrivacyName(text.toString())) {
                return view;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findSettingsView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void dismissDrawer(View view) {
        if (view == null) return;
        dismissSheet(view);
        Activity activity = Views.activity(view.getContext());
        if (activity == null && resumed != null) activity = resumed.get();
        if (activity != null) {
            final Activity act = activity;
            act.runOnUiThread(() -> {
                try {
                    act.onBackPressed();
                } catch (Throwable t) {
                    try {
                        Method getDispatcher = act.getClass().getMethod("getOnBackPressedDispatcher");
                        Object dispatcher = getDispatcher.invoke(act);
                        if (dispatcher != null) {
                            Method bp = dispatcher.getClass().getMethod("onBackPressed");
                            bp.invoke(dispatcher);
                        }
                    } catch (Throwable ignored) { }
                }
            });
        }
    }

    private static void dismissSheet(View view) {
        Activity activity = Views.activity(view.getContext());
        if (activity == null && resumed != null) activity = resumed.get();
        if (activity != null) {
            try {
                Method getFragments = activity.getClass().getMethod("getSupportFragmentManager");
                Object fm = getFragments.invoke(activity);
                if (fm != null) {
                    Method listMethod = fm.getClass().getMethod("getFragments");
                    List<?> fragments = (List<?>) listMethod.invoke(fm);
                    if (fragments != null) {
                        for (Object f : fragments) {
                            if (f != null && f.getClass().getName().contains("Sheet")) {
                                try {
                                    Method dismiss = f.getClass().getMethod("dismissAllowingStateLoss");
                                    dismiss.invoke(f);
                                } catch (Throwable t) {
                                    try {
                                        Method dismiss = f.getClass().getMethod("dismiss");
                                        dismiss.invoke(f);
                                    } catch (Throwable ignored) { }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    @SuppressLint("DiscouragedApi")
    private static View createMenuRow(Context context, View templateView, Runnable onDismiss) {
        Context ctx = templateView != null ? templateView.getContext() : context;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        int dp16 = dp(ctx, 16);
        int dp12 = dp(ctx, 12);
        int dp20 = dp(ctx, 20);
        int dp24 = dp(ctx, 24);

        row.setPadding(dp16, dp12, dp16, dp12);
        row.setMinimumHeight(dp(ctx, 48));

        // Click ripple
        TypedValue outValue = new TypedValue();
        if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            row.setBackgroundResource(outValue.resourceId);
        }

        boolean isDark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int textColor = isDark ? 0xFFE0E0E0 : 0xFF161823;
        int iconColor = isDark ? 0xFFBBBBBB : 0xFF555555;

        // Try TuxIconView or standard ImageView for puzzle icon
        View iconView = null;
        Class<?> tuxIconClass = Hooks.findClass("com.bytedance.tux.icon.TuxIconView", ctx.getClassLoader());
        int puzzleId = ctx.getResources().getIdentifier("icon_puzzle", "raw", ctx.getPackageName());
        if (puzzleId == 0) puzzleId = ctx.getResources().getIdentifier("icon_settings", "raw", ctx.getPackageName());

        if (tuxIconClass != null && puzzleId != 0) {
            try {
                Constructor<?> c = tuxIconClass.getConstructor(Context.class);
                iconView = (View) c.newInstance(ctx);
                Method setIconRes = tuxIconClass.getMethod("setIconRes", int.class);
                setIconRes.invoke(iconView, puzzleId);
            } catch (Throwable ignored) { }
        }
        if (iconView == null) {
            ImageView iv = new ImageView(ctx);
            if (puzzleId != 0) {
                try { iv.setImageResource(puzzleId); } catch (Throwable ignored) { }
            }
            if (iv.getDrawable() == null) {
                iv.setImageResource(android.R.drawable.ic_menu_preferences);
            }
            iv.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
            iconView = iv;
        }

        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp24, dp24);
        iconParams.rightMargin = dp16;
        row.addView(iconView, iconParams);

        // Title TextView
        TextView textView = null;
        Class<?> tuxTextClass = Hooks.findClass("com.bytedance.tux.input.TuxTextView", ctx.getClassLoader());
        if (tuxTextClass != null) {
            try {
                Constructor<?> c = tuxTextClass.getConstructor(Context.class);
                textView = (TextView) c.newInstance(ctx);
            } catch (Throwable ignored) { }
        }
        if (textView == null) {
            textView = new TextView(ctx);
        }

        Activity act = resumed.get();
        if (act != null) I18n.use(act.getResources().getConfiguration().getLocales().get(0));
        textView.setText(I18n.get(I18n.S.APP_NAME));
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        textView.setTextColor(textColor);
        textView.setTypeface(Typeface.DEFAULT_BOLD);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        row.addView(textView, textParams);

        // Chevron Right Arrow >
        int chevronId = ctx.getResources().getIdentifier("icon_chevron_right_offset_fill_ltr", "raw", ctx.getPackageName());
        if (chevronId == 0) chevronId = ctx.getResources().getIdentifier("icon_chevron_right_ltr", "raw", ctx.getPackageName());
        if (chevronId != 0) {
            View arrowView = null;
            if (tuxIconClass != null) {
                try {
                    Constructor<?> c = tuxIconClass.getConstructor(Context.class);
                    arrowView = (View) c.newInstance(ctx);
                    Method setIconRes = tuxIconClass.getMethod("setIconRes", int.class);
                    setIconRes.invoke(arrowView, chevronId);
                } catch (Throwable ignored) { }
            }
            if (arrowView == null) {
                ImageView iv = new ImageView(ctx);
                try { iv.setImageResource(chevronId); } catch (Throwable ignored) { }
                iv.setColorFilter(isDark ? 0xFF777777 : 0xFFAAAAAA, PorterDuff.Mode.SRC_IN);
                arrowView = iv;
            }
            LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp20, dp20);
            row.addView(arrowView, arrowParams);
        }

        row.setOnClickListener(v -> {
            if (onDismiss != null) {
                try { onDismiss.run(); } catch (Throwable ignored) { }
            }
            open(v.getContext());
        });

        return row;
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- Fallback row inside Settings and privacy ---------------------------------------

    private static void injectRow(ClassLoader loader) throws Exception {
        Class<?> itemType = typeArgument(Class.forName(PRIVACY_GROUP, false, loader), 0);
        if (itemType == null || !itemType.isInterface()) throw new IllegalStateException("privacy item type");
        item = Proxy.newProxyInstance(itemType.getClassLoader(), new Class<?>[]{itemType}, SettingsEntry::itemMethod);

        Method combiner = null;
        Method factory = null;
        for (String call : Discovery.invokedBy(FRAGMENT, "onCreate")) {
            String[] parts = call.split("#", 2);
            Class<?> owner = Hooks.findClass(parts[0], loader);
            if (owner == null) continue;
            for (Method method : owner.getDeclaredMethods()) {
                if (!method.getName().equals(parts[1]) || !Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] p = method.getParameterTypes();
                if (p.length == 2 && p[0] == Iterable.class && List.class.isAssignableFrom(method.getReturnType())) {
                    combiner = method;
                } else if (p.length == 2 && p[1].isAssignableFrom(itemType) && method.getReturnType() == void.class) {
                    factory = method;
                }
            }
        }
        if (combiner == null || factory == null) throw new IllegalStateException("settings list helpers");

        Hooks.hook(combiner, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                Object result = param.getResult();
                if (!(result instanceof List<?>) || ((List<?>) result).contains(item)) return;
                List<Object> list = new ArrayList<>((List<?>) result);
                list.add(0, item);
                param.setResult(list);
            }
        });
        int sortHooks = 0;
        Class<?> baseItem = factory.getParameterTypes()[1];
        for (Method method : factory.getDeclaringClass().getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == int.class
                    && p.length == 1 && p[0] == baseItem) {
                Hooks.hook(method, new Hooks.Hook() {
                    @Override protected void before(Hooks.Call param) {
                        if (param.args[0] == item) param.setResult(-100);
                    }
                });
                sortHooks++;
            }
        }

        Class<?> privacyItem = Class.forName(PRIVACY_ITEM, false, loader);
        Field keyField = null;
        for (Field field : privacyItem.getDeclaredFields()) {
            if (field.getType() == String.class && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                keyField = field;
                break;
            }
        }
        Class<?> stateType = typeArgument(privacyItem, 0);
        if (keyField == null || stateType == null) throw new IllegalStateException("privacy cell shape");
        Field key = keyField;
        Hooks.hookAll(privacyItem, "defaultState", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                try {
                    if (!KEY.equals(key.get(param.thisObject))) return;
                    if (state == null) state = buildState(stateType, loader);
                    param.setResult(state);
                } catch (Throwable error) {
                    RuntimeLog.log("settings row state: " + error);
                }
            }
        });
        RuntimeLog.log("settings row installed; sort=" + sortHooks + " item=" + itemType.getName());
    }

    private static Object itemMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals": return args != null && args.length == 1 && proxy == args[0];
            case "hashCode": return KEY.hashCode();
            case "toString": return "TiktokPatchXposedItem";
            case "getContentType": return "cellDisclosure";
            case "getHeadingItem": return false;
            default: break;
        }
        Class<?> type = method.getReturnType();
        if (type == String.class) return KEY;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }

    /** PrivacyItemState(icon, searchIcon, rightLabel, key, title, eventSink, action). */
    private static Object buildState(Class<?> stateType, ClassLoader loader) throws Exception {
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : stateType.getDeclaredConstructors()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 7 && p[2] == String.class && p[3] == String.class && p[4] == String.class) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) throw new NoSuchMethodException("PrivacyItemState constructor");
        constructor.setAccessible(true);
        Class<?>[] p = constructor.getParameterTypes();
        Object icon = icon(p[0], loader);
        Object eventSink = function(p[5], loader);
        Object action = function(p[6], loader);
        Activity activity = resumed.get();
        if (activity != null) I18n.use(activity.getResources().getConfiguration().getLocales().get(0));
        return constructor.newInstance(icon, icon, "", KEY, I18n.get(I18n.S.APP_NAME), eventSink, action);
    }

    @SuppressLint("DiscouragedApi")
    private static Object icon(Class<?> type, ClassLoader loader) {
        Application app = application;
        if (app == null) return null;
        int id = app.getResources().getIdentifier("icon_puzzle", "raw", app.getPackageName());
        if (id == 0) return null;
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(id);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Object function(Class<?> type, ClassLoader loader) {
        Object unit = kotlinUnit(loader);
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return args != null && proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                return "TiktokPatchXposedClick";
            }
            if ("invoke".equals(method.getName())) {
                Context context = null;
                if (args != null) {
                    for (Object arg : args) {
                        context = arg instanceof Context ? (Context) arg : contextField(arg);
                        if (context != null) break;
                    }
                }
                open(context);
            }
            return unit;
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Context contextField(Object event) {
        if (event == null) return null;
        for (Class<?> type = event.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(event);
                    if (value instanceof Context) return (Context) value;
                } catch (Throwable ignored) { }
            }
        }
        return null;
    }

    private static Object kotlinUnit(ClassLoader loader) {
        try {
            Class<?> unit = Class.forName("kotlin.Unit", false, loader);
            for (Field field : unit.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == unit) {
                    field.setAccessible(true);
                    return field.get(null);
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static Class<?> typeArgument(Class<?> type, int index) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Type generic = current.getGenericSuperclass();
            if (generic instanceof ParameterizedType) {
                Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
                if (arguments.length > index && arguments[index] instanceof Class<?>) return (Class<?>) arguments[index];
            }
        }
        return null;
    }

    // ---- activities --------------------------------------------------------------------

    private static void trackActivities(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                resumed = new WeakReference<>(activity);
                if (openPending && !activity.isFinishing() && !(activity instanceof ModSettingsActivity)) {
                    openPending = false;
                    activity.getWindow().getDecorView().postDelayed(() -> open(activity), 250);
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) {
                Intent intent = activity.getIntent();
                if (state == null && intent != null && intent.getBooleanExtra(OPEN_EXTRA, false)) {
                    intent.removeExtra(OPEN_EXTRA);
                    openPending = true;
                }
            }

            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }
}
