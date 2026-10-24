package com.golda.patchertiktok;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * TikTok sends bio links to its Spark web containers (SparkActivity, SparkThirdActivity...) with a
 * SparkContextContainerId, rather than a URL extra.
 * Remember weak context references at the readable SparkContext constructor, then intercept
 * framework launches of known browser containers. A successful ACTION_VIEW replaces the launch;
 * missing anchors, internal URLs and failed browser launches retain TikTok's original flow.
 */
final class ExternalLinks {
    private static final String SPARK = "com.bytedance.hybrid.spark.SparkContext";
    private static final String SPARK_PACKAGE = "com.bytedance.hybrid.spark.";
    private static final String[] ACTIVITIES = {
            "com.bytedance.hybrid.spark.page.SparkActivity",
            "com.ss.android.ugc.aweme.crossplatform.activity.CrossPlatformActivity"};
    private static final String[] INTERNAL_DOMAINS = {
            "tiktok.com", "tiktokv.com", "tiktokv.eu", "tiktokv.us", "tiktokw.eu", "tiktokw.us",
            "tiktokv-eu.com", "tiktokv-us.com", "tiktokcdn.com", "tiktokcdn.eu",
            "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktok.net", "musical.ly",
            "byteoversea.com", "byteoversea.net", "bytedance.com", "bytedance.net",
            "tiktok.shop", "tiktokshop.com", "tiktokglobalshop.com", "tiktok-row.net",
            "byteintl.com", "byteintlapi.com", "ttapis.com", "ttwstatic.com", "ttlstatic.com",
            "oecstatic.com", "ibytedtos.com", "ibyteimg.com", "byteimg.com", "pstatp.com"};
    private static final ThreadLocal<Boolean> REDIRECTING = new ThreadLocal<>();
    private static final String BIO_LINK = "com.ss.android.ugc.profile.business.profile.BioProfileLinkComponent";
    private static final String LINK_DATA = "com.ss.android.ugc.profile.business.profile.ProfileLinkData";
    /** The profile link the user just opened; Spark launches for it carry only a container id. */
    private static volatile String bioUrl;
    private static volatile long bioTime;

    private ExternalLinks() { }

    static void install(ClassLoader loader) {
        Set<String> browsers = new HashSet<>();
        for (String name : ACTIVITIES) {
            Class<?> type = Hooks.findClass(name, loader);
            if (type != null && Activity.class.isAssignableFrom(type)) browsers.add(name);
        }
        if (browsers.isEmpty()) {
            RuntimeLog.log("external browser unavailable: browser activities not found");
            return;
        }
        SparkLookup spark = SparkLookup.find(loader);
        int bio = rememberBioLinks(loader);
        Hooks.Hook launch = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (!Prefs.on(Prefs.EXTERNAL_BROWSER) || Boolean.TRUE.equals(REDIRECTING.get())) return;
                try {
                    Context context = param.thisObject instanceof Context ? (Context) param.thisObject : null;
                    Intent intent = null;
                    for (Object arg : param.args) {
                        if (context == null && arg instanceof Context) context = (Context) arg;
                        if (arg instanceof Intent) intent = (Intent) arg;
                    }
                    if (context == null || intent == null) return;
                    ComponentName component = intent.getComponent();
                    if (component == null || !context.getPackageName().equals(component.getPackageName())
                            || !isBrowser(component.getClassName(), browsers)) return;
                    String url = destination(intent, spark);
                    if (!isExternalUrl(url)) return;
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    browser.addCategory(Intent.CATEGORY_BROWSABLE);
                    browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // A hostless probe selects the browser's general https handler.
                    try {
                        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://"));
                        probe.addCategory(Intent.CATEGORY_BROWSABLE);
                        ResolveInfo resolved = context.getPackageManager().resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
                        if (resolved != null && resolved.activityInfo != null
                                && !"android".equals(resolved.activityInfo.packageName)
                                && !context.getPackageName().equals(resolved.activityInfo.packageName)) {
                            browser.setPackage(resolved.activityInfo.packageName);
                        }
                    } catch (Throwable ignored) { }
                    REDIRECTING.set(Boolean.TRUE);
                    try {
                        context.startActivity(browser);
                        param.setResult(null);
                    } finally {
                        REDIRECTING.remove();
                    }
                } catch (Throwable ignored) { }
            }
        };
        int hooks = hookAll(Activity.class, "startActivity", launch)
                + hookAll(ContextWrapper.class, "startActivity", launch);
        for (Method method : Instrumentation.class.getDeclaredMethods()) {
            if (!method.getName().equals("execStartActivity") || Modifier.isAbstract(method.getModifiers())) continue;
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter != Intent.class) continue;
                try {
                    Hooks.hook(method, launch);
                    hooks++;
                } catch (Throwable ignored) { }
                break;
            }
        }
        RuntimeLog.log("external browser: activities=" + browsers.size() + " hooks=" + hooks
                + " sparkLookup=" + (spark != null) + " bio=" + bio + (hooks == 0 ? " (unavailable)" : ""));
    }

    /** TikTok's web containers: the known activities and any other screen of its Spark web framework. */
    static boolean isBrowser(String name, Set<String> known) {
        return known.contains(name) || name.startsWith(SPARK_PACKAGE);
    }

    /** The profile's link component hands its ProfileLinkData to the method that opens it. */
    private static int rememberBioLinks(ClassLoader loader) {
        Class<?> component = Hooks.findClass(BIO_LINK, loader);
        Class<?> data = Hooks.findClass(LINK_DATA, loader);
        if (component == null || data == null) return 0;
        int hooks = 0;
        for (Method method : component.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (p.length != 1 || p[0] != data || method.getReturnType() != void.class) continue;
            Hooks.hook(method, new Hooks.Hook() {
                @Override protected void before(Hooks.Call param) {
                    Object url = Reflect.call(param.args[0], "getUrl");
                    if (url instanceof String) {
                        bioUrl = (String) url;
                        bioTime = android.os.SystemClock.uptimeMillis();
                    }
                }
            });
            hooks++;
        }
        return hooks;
    }

    private static int hookAll(Class<?> type, String name, Hooks.Hook hook) {
        try {
            return Hooks.hookAll(type, name, hook);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String destination(Intent intent, SparkLookup spark) throws Throwable {
        String data = intent.getDataString();
        if (data != null) return browserUrl(data);
        String url;
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        if (bioUrl != null && android.os.SystemClock.uptimeMillis() - bioTime < 3_000L) {
            String recent = browserUrl(bioUrl);
            bioUrl = null;
            if (recent != null) return recent;
        }
        for (String key : new String[]{"url", "sparkInsureUrl"}) {
            Object value = extras.get(key);
            url = value instanceof String ? browserUrl((String) value) : null;
            if (url != null) return url;
        }
        for (String key : new String[]{"SparkContextContainerId", "ContainerId"}) {
            Object id = extras.get(key);
            if (spark == null || !(id instanceof String)) continue;
            url = spark.url((String) id);
            if (url != null) return url;
        }
        // Unknown extra keys are accepted only when they identify one unambiguous destination.
        String found = null;
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            url = value instanceof String ? browserUrl((String) value) : null;
            if (url == null) continue;
            if (found != null && !found.equals(url)) return null;
            found = url;
        }
        return found;
    }

    static boolean isExternalUrl(String url) {
        URI uri = webUri(url);
        if (uri == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        for (String domain : INTERNAL_DOMAINS) {
            if (host.equals(domain) || host.endsWith("." + domain)) return false;
        }
        return true;
    }

    /** Unwrap only the webview route observed in Spark; other deep links remain untouched. */
    static String browserUrl(String value) {
        if (webUri(value) != null) return value;
        if (value == null) return null;
        try {
            URI uri = new URI(value);
            if (!"aweme".equalsIgnoreCase(uri.getScheme()) || !"webview".equalsIgnoreCase(uri.getHost())) return null;
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) return null;
            String query = uri.getRawQuery();
            if (query == null) return null;
            for (String pair : query.split("&")) {
                if (!pair.startsWith("url=")) continue;
                String url = pair.substring(4);
                if (webUri(url) == null) url = URLDecoder.decode(url, StandardCharsets.UTF_8.name());
                return webUri(url) == null ? null : url;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static URI webUri(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if ((!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isEmpty() || uri.getPort() > 65535) return null;
            return uri;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static final class SparkLookup {
        final Deque<WeakReference<Object>> contexts = new ArrayDeque<>();

        static SparkLookup find(ClassLoader loader) {
            try {
                Class<?> spark = Hooks.findClass(SPARK, loader);
                if (spark == null || Reflect.field(spark, "fullUrl") == null
                        || Reflect.field(spark, "containerId") == null) return null;
                SparkLookup lookup = new SparkLookup();
                int hooks = 0;
                for (Constructor<?> constructor : spark.getDeclaredConstructors()) {
                    try {
                        Hooks.hook(constructor, new Hooks.Hook() {
                            @Override protected void after(Hooks.Call param) {
                                if (!Prefs.on(Prefs.EXTERNAL_BROWSER) || param.hasThrowable()) return;
                                try {
                                    synchronized (lookup.contexts) {
                                        lookup.contexts.removeIf(ref -> ref.get() == null || ref.get() == param.thisObject);
                                        lookup.contexts.addFirst(new WeakReference<>(param.thisObject));
                                        if (lookup.contexts.size() > 64) lookup.contexts.removeLast();
                                    }
                                } catch (Throwable ignored) { }
                            }
                        });
                        hooks++;
                    } catch (Throwable ignored) { }
                }
                return hooks == 0 ? null : lookup;
            } catch (Throwable ignored) {
                return null;
            }
        }

        String url(String id) {
            try {
                synchronized (contexts) {
                    for (WeakReference<Object> ref : contexts) {
                        Object context = ref.get();
                        if (context == null || !id.equals(Reflect.get(context, "containerId"))) continue;
                        Object value = Reflect.call(Reflect.get(context, "schemaBundle"), "getUrl");
                        String url = value instanceof String ? browserUrl((String) value) : null;
                        if (url != null) return url;
                        value = Reflect.get(context, "fullUrl");
                        return value instanceof String ? browserUrl((String) value) : null;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
