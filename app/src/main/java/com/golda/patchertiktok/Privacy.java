package com.golda.patchertiktok;

import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.NetworkCapabilities;
import android.provider.Settings;

import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * What TikTok learns about the device: its location, a VPN in use and the Android ID. Every hook
 * is on Android's own API, so it does not depend on TikTok's version.
 */
final class Privacy {
    private static final String[] LOCATION_PERMISSIONS = {
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
    };

    private Privacy() { }

    static void install() {
        int hooks = installLocation() + installVpn() + installAndroidId();
        RuntimeLog.log("privacy: hooks=" + hooks);
    }

    /**
     * Location: TikTok is told the permission is not granted, location is off, and any reading or
     * subscription returns nothing, so neither GPS nor network location reaches it.
     */
    private static int installLocation() {
        Hooks.Hook denied = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.BLOCK_LOCATION) || !(param.args[0] instanceof String)) return;
                if (isLocation((String) param.args[0])) param.setResult(PackageManager.PERMISSION_DENIED);
            }
        };
        int hooks = 0;
        Class<?> context = Hooks.findClass("android.app.ContextImpl", Privacy.class.getClassLoader());
        if (context != null) {
            hooks += Hooks.hookAll(context, "checkPermission", denied);
            hooks += Hooks.hookAll(context, "checkSelfPermission", denied);
            hooks += Hooks.hookAll(context, "checkCallingOrSelfPermission", denied);
        }
        hooks += Hooks.hookAll(android.content.ContextWrapper.class, "checkSelfPermission", denied);
        Class<?> packages = Hooks.findClass("android.app.ApplicationPackageManager", Privacy.class.getClassLoader());
        if (packages != null) hooks += Hooks.hookAll(packages, "checkPermission", denied);
        Hooks.Hook nothing = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.BLOCK_LOCATION)) param.setResult(null);
            }
        };
        Hooks.Hook off = new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (Prefs.on(Prefs.BLOCK_LOCATION)) param.setResult(false);
            }
        };
        for (String name : new String[]{"getLastKnownLocation", "requestLocationUpdates", "requestSingleUpdate",
                "getCurrentLocation", "addGpsStatusListener", "registerGnssStatusCallback", "addProximityAlert"}) {
            hooks += Hooks.hookAll(LocationManager.class, name, name.startsWith("add") || name.startsWith("register")
                    ? new Hooks.Hook() {
                        @Override protected void before(Hooks.Call param) {
                            if (!Prefs.on(Prefs.BLOCK_LOCATION)) return;
                            Class<?> result = ((java.lang.reflect.Method) param.method).getReturnType();
                            param.setResult(result == boolean.class ? Boolean.FALSE : null);
                        }
                    } : nothing);
        }
        hooks += Hooks.hookAll(LocationManager.class, "isLocationEnabled", off);
        hooks += Hooks.hookAll(LocationManager.class, "isProviderEnabled", off);
        return hooks;
    }

    private static boolean isLocation(String permission) {
        for (String location : LOCATION_PERMISSIONS) if (location.equals(permission)) return true;
        return false;
    }

    /** VPN: the network never reports a VPN transport, and tunnel interfaces are not listed. */
    private static int installVpn() {
        int hooks = Hooks.hookAll(NetworkCapabilities.class, "hasTransport", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(Prefs.HIDE_VPN) && Integer.valueOf(NetworkCapabilities.TRANSPORT_VPN).equals(param.args[0])) {
                    param.setResult(false);
                }
            }
        });
        hooks += Hooks.hookAll(NetworkCapabilities.class, "hasCapability", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(Prefs.HIDE_VPN) && Integer.valueOf(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).equals(param.args[0])) {
                    param.setResult(true);
                }
            }
        });
        hooks += Hooks.hookAll(android.net.ConnectivityManager.class, "getNetworkInfo", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                // getNetworkInfo(TYPE_VPN) asks for the VPN directly.
                if (Prefs.on(Prefs.HIDE_VPN) && Integer.valueOf(17).equals(param.args.length > 0 ? param.args[0] : null)) {
                    param.setResult(null);
                }
            }
        });
        hooks += Hooks.hookAll(android.net.LinkProperties.class, "getInterfaceName", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(Prefs.HIDE_VPN) && param.getResult() instanceof String && isTunnel((String) param.getResult())) {
                    param.setResult("wlan0");
                }
            }
        });
        hooks += Hooks.hookAll(NetworkInterface.class, "getNetworkInterfaces", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.HIDE_VPN) || !(param.getResult() instanceof Enumeration)) return;
                List<NetworkInterface> kept = new ArrayList<>();
                for (Object item : Collections.list((Enumeration<?>) param.getResult())) {
                    NetworkInterface network = (NetworkInterface) item;
                    if (!isTunnel(network.getName())) kept.add(network);
                }
                param.setResult(Collections.enumeration(kept));
            }
        });
        Hooks.Hook byName = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                Object result = param.getResult();
                if (Prefs.on(Prefs.HIDE_VPN) && result instanceof NetworkInterface && isTunnel(((NetworkInterface) result).getName())) {
                    param.setResult(null);
                }
            }
        };
        hooks += Hooks.hookAll(NetworkInterface.class, "getByName", byName);
        hooks += Hooks.hookAll(NetworkInterface.class, "getByIndex", byName);
        hooks += Hooks.hookAll(NetworkInterface.class, "getByInetAddress", byName);
        return hooks;
    }

    private static boolean isTunnel(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("tun") || lower.startsWith("ppp") || lower.startsWith("tap")
                || lower.startsWith("wg") || lower.startsWith("ipsec") || lower.startsWith("utun");
    }

    /** Android ID: a random one made once for this TikTok, so the real one is never shown. */
    private static int installAndroidId() {
        return Hooks.hookAll(Settings.Secure.class, "getString", new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.SPOOF_ANDROID_ID) || param.args.length < 2) return;
                if (Settings.Secure.ANDROID_ID.equals(param.args[1])) param.setResult(androidId());
            }
        });
    }

    static String androidId() {
        String id = Prefs.text(Prefs.ANDROID_ID);
        if (id != null && id.matches("[0-9a-f]{16}")) return id;
        id = newAndroidId();
        Prefs.setText(Prefs.ANDROID_ID, id);
        return id;
    }

    static String newAndroidId() {
        byte[] bytes = new byte[8];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(Locale.ROOT, "%02x", b));
        // Android IDs never start with a zero digit in practice; keep the look of a real one.
        if (hex.charAt(0) == '0') hex.setCharAt(0, 'a');
        return hex.toString();
    }
}
