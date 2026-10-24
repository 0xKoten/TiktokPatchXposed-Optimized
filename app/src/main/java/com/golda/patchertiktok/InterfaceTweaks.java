package com.golda.patchertiktok;

/**
 * Smaller interface switches. The Inbox builds its widgets from injectors that each say whether
 * they are enabled; the stories row above the chats ("skylight") is one of them. Without an account
 * TikTok asks MandatoryLoginService whether to force the sign-in screen.
 */
final class InterfaceTweaks {
    private static final String INBOX_STORIES = "com.ss.android.ugc.aweme.inbox.skylight.InboxSkylightWidgetV2Injector";
    private static final String LOGIN_WALL = "com.ss.android.ugc.aweme.services.MandatoryLoginService";

    private InterfaceTweaks() { }

    static void install(ClassLoader loader) {
        Class<?> stories = Hooks.findClass(INBOX_STORIES, loader);
        if (stories == null) {
            RuntimeLog.log("inbox stories switch unavailable");
        } else {
            Hooks.hookAll(stories, "enable", new Hooks.Hook() {
                @Override protected void after(Hooks.Call param) {
                    if (Prefs.on(Prefs.HIDE_INBOX_STORIES)) param.setResult(false);
                }
            });
        }
        Class<?> login = Hooks.findClass(LOGIN_WALL, loader);
        if (login == null) {
            RuntimeLog.log("login wall switch unavailable");
            return;
        }
        Hooks.Hook never = new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (Prefs.on(Prefs.NO_LOGIN_WALL) && param.getResult() instanceof Boolean) param.setResult(false);
            }
        };
        Hooks.hookAll(login, "enableForcedLogin", never);
        Hooks.hookAll(login, "shouldShowForcedLogin", never);
    }
}
