package com.golda.patchertiktok;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps existing streaks alive while TikTok is in the foreground. No alarms, wake locks or
 * services: checks start ~20 s after TikTok opens and stop as soon as it goes to the background.
 */
final class AutoStreakManager {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final Runnable CHECK = AutoStreakManager::startCheck;
    private static final long MIN_CHECK_INTERVAL_MS = 300_000L;
    private static final java.util.Random RANDOM = new java.util.Random();
    private static volatile Application context;
    private static volatile ClassLoader loader;
    private static volatile boolean foreground;
    private static volatile Thread worker;
    private static volatile StreakApi api;
    private static volatile StreakSender sender;
    private static volatile long lastCheckElapsed;
    private static volatile int startupRetries;
    /** When TikTok last came to the foreground; messages from other phones arrive after that. */
    private static volatile long foregroundSince;
    private static volatile int syncWaits;
    private static volatile StreakSentCheck sentCheck;
    private static volatile boolean sentCheckFailed;
    private static boolean lastEnabled;
    private static long lastTime;
    private static String lastExcluded = "";

    private AutoStreakManager() { }

    static void install(Application app, ClassLoader classLoader) {
        context = app;
        loader = classLoader;
        StreakApi.capture(classLoader);
        StreakSentCheck.capture(classLoader);
        lastEnabled = enabled();
        lastTime = Prefs.number(Prefs.STREAK_TIME);
        lastExcluded = Prefs.on(Prefs.STREAK_ONLY_SELECTED) + Prefs.text(Prefs.STREAK_EXCLUDED);
        Prefs.listen(() -> MAIN.post(AutoStreakManager::onSettingsChanged));
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private int started;

            @Override public void onActivityStarted(Activity activity) {
                if (started++ == 0) onForeground();
            }

            @Override public void onActivityStopped(Activity activity) {
                if (started > 0 && --started == 0) onBackground();
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    private static boolean enabled() { return Prefs.on(Prefs.AUTO_STREAK); }

    private static void onSettingsChanged() {
        boolean now = enabled();
        long time = Prefs.number(Prefs.STREAK_TIME);
        String excluded = Prefs.on(Prefs.STREAK_ONLY_SELECTED) + Prefs.text(Prefs.STREAK_EXCLUDED);
        boolean refined = time != lastTime || !excluded.equals(lastExcluded);
        lastTime = time;
        lastExcluded = excluded;
        if (now == lastEnabled && !(now && refined)) return;
        lastEnabled = now;
        if (!now) stop();
        // A new time or recipient list is looked at on the next check, not the moment it is picked.
        else if (foreground && !RUNNING.get()) schedule(openDelay());
    }

    private static void onForeground() {
        foreground = true;
        startupRetries = 0;
        syncWaits = 0;
        foregroundSince = SystemClock.elapsedRealtime();
        if (enabled()) {
            long remaining = MIN_CHECK_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastCheckElapsed);
            schedule(Math.max(openDelay(), remaining));
        }
    }

    /** A person opens the inbox at some point, not exactly N seconds after launch. */
    private static long openDelay() { return between(60_000L, 120_000L); }

    private static long between(long from, long to) { return from + (long) (RANDOM.nextDouble() * (to - from)); }

    private static void onBackground() {
        foreground = false;
        stop();
    }

    private static void stop() {
        MAIN.removeCallbacks(CHECK);
        Thread active = worker;
        if (active != null) active.interrupt();
    }

    private static synchronized void ensureBindings() throws Exception {
        if (api == null) api = new StreakApi(loader);
        if (sender == null) sender = new StreakSender(context, loader);
    }

    private static void schedule(long delay) {
        MAIN.removeCallbacks(CHECK);
        if (enabled() && foreground) MAIN.postDelayed(CHECK, Math.max(1_000L, delay));
    }

    private static void startCheck() {
        if (!enabled() || !foreground || !RUNNING.compareAndSet(false, true)) return;
        worker = new Thread(() -> {
            try {
                ensureBindings();
                String account = api.account();
                List<?> items = api.items();
                if (account == null || items == null) {
                    if (startupRetries++ < 2) schedule(60_000L);
                    return;
                }
                // Until TikTok has pulled new messages, a flame sent from another phone is not known here.
                if (!StreakSentCheck.pulledSince(foregroundSince)) {
                    if (syncWaits++ < 6) schedule(20_000L);
                    else RuntimeLog.log("streak check: messages not synced, nothing sent");
                    return;
                }
                lastCheckElapsed = SystemClock.elapsedRealtime();
                scan(account, items);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (Throwable error) {
                // A missing provider right after launch is retried; anything else stops quietly.
                if (startupRetries++ < 2) schedule(60_000L);
                RuntimeLog.log("streak check stopped: " + error.getClass().getSimpleName() + " " + error.getMessage());
            } finally {
                worker = null;
                RUNNING.set(false);
            }
        }, "tiktok-streak-check");
        worker.setDaemon(true);
        worker.start();
    }

    private static void scan(String account, List<?> items) throws Exception {
        List<StreakApi.Candidate> candidates = candidates(items, account);
        int confirmed = 0;
        int unresolved = 0;
        int skipped = 0;
        int waiting = 0;
        long waitUntil = Long.MAX_VALUE;
        java.util.Set<String> excluded = excluded();
        int minute = StreakTiming.minuteOfDay(Prefs.number(Prefs.STREAK_TIME));
        for (StreakApi.Candidate initial : candidates) {
            if (Thread.currentThread().isInterrupted() || !foreground || !enabled()) break;
            if (!account.equals(api.account())) break;
            if (excluded.contains(initial.conversation)) {
                skipped++;
                continue;
            }
            long when = StreakTiming.sendAt(System.currentTimeMillis(), StreakKeys.seconds(initial.end) * 1000L,
                    minute, java.util.TimeZone.getDefault());
            if (when > 0) {
                waitUntil = Math.min(waitUntil, when);
                waiting++;
                continue;
            }
            // Re-read before dispatch: a manual message may already have renewed the streak.
            StreakApi.Candidate current = null;
            List<?> fresh = api.items();
            if (fresh == null) break;
            for (StreakApi.Candidate candidate : candidates(fresh, account)) {
                if (initial.conversation.equals(candidate.conversation)
                        && initial.peer.equals(candidate.peer) && initial.window().equals(candidate.window())) {
                    current = candidate;
                    break;
                }
            }
            if (current == null) {
                RuntimeLog.log("streak window " + initial.window() + ": renewed meanwhile");
                skipped++;
                continue;
            }
            StreakApi.Candidate selected = current;
            // Already sent today, by hand or from another phone with the same account: the friend's turn.
            if (sentSince(account, selected) == StreakSentCheck.Answer.SENT) {
                RuntimeLog.log("streak window " + selected.window() + ": already sent today");
                skipped++;
                continue;
            }
            String day =new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            String result = sender.send(account, selected, day, () -> {
                if (!enabled() || !foreground || !selected.atRisk(System.currentTimeMillis())) return false;
                try {
                    return account.equals(api.account());
                } catch (ReflectiveOperationException error) {
                    return false;
                }
            });
            RuntimeLog.log("streak window " + selected.window() + ": " + result);
            if ("CONFIRMED".equals(result)) confirmed++;
            else if ("UNKNOWN".equals(result) || "RETRY".equals(result)) unresolved++;
            else skipped++;
            // TikTok rejected or never confirmed the message: let the user know.
            if ("UNKNOWN".equals(result)) StreakNotice.failed(context);
            // Like going through chats by hand: a few seconds to open the next one and tap the flame.
            if (!"PREVIOUS_ATTEMPT".equals(result) && !"CANCELLED".equals(result)) Thread.sleep(between(8_000L, 18_000L));
        }
        long next = Long.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (Object item : items) next = Math.min(next, api.nextRiskMillis(item, now));
        RuntimeLog.log("streak check: records=" + items.size() + " " + api.summary(items, now)
                + " eligible=" + candidates.size() + " confirmed=" + confirmed + " unresolved=" + unresolved
                + " skipped=" + skipped + " waiting=" + waiting
                + (next == Long.MAX_VALUE ? "" : " nextRiskIn=" + (next - now) / 60_000L + "m")
                + (waitUntil == Long.MAX_VALUE ? "" : " sendIn=" + (waitUntil - now) / 60_000L + "m"));
        long delay = Long.MAX_VALUE;
        if (next != Long.MAX_VALUE) delay = Math.max(MIN_CHECK_INTERVAL_MS, next - now + between(60_000L, 600_000L));
        // The chosen time: a minute or two after it, like opening the chat when the reminder rings.
        if (waitUntil != Long.MAX_VALUE) delay = Math.min(delay, Math.max(60_000L, waitUntil - now + between(30_000L, 150_000L)));
        if (delay != Long.MAX_VALUE) schedule(delay);
    }

    static ClassLoader loader() { return loader; }

    /** A one-to-one chat with a streak, for the recipients list. */
    static final class Partner {
        final String conversation;
        final String peer;
        final long days;

        Partner(String conversation, String peer, long days) {
            this.conversation = conversation;
            this.peer = peer;
            this.days = days;
        }
    }

    /** Current streak chats, longest first; null when TikTok's streak data is not loaded yet. Off the main thread. */
    static List<Partner> partners() {
        try {
            ensureBindings();
            String account = api.account();
            List<?> items = api.items();
            if (account == null || items == null) return null;
            return api.partners(items, account);
        } catch (Throwable error) {
            return null;
        }
    }

    /** Conversations the user took out of auto streaks; none while "everyone" is chosen. */
    static java.util.Set<String> excluded() {
        java.util.Set<String> result = new java.util.HashSet<>();
        if (!Prefs.on(Prefs.STREAK_ONLY_SELECTED)) return result;
        for (String id : Prefs.text(Prefs.STREAK_EXCLUDED).split(",")) {
            if (!id.trim().isEmpty()) result.add(id.trim());
        }
        return result;
    }

    /** UNKNOWN when TikTok's history cannot be read; the flame then goes as before. */
    private static StreakSentCheck.Answer sentSince(String account, StreakApi.Candidate candidate) {
        if (sentCheck == null && !sentCheckFailed) {
            try {
                sentCheck = new StreakSentCheck(loader);
            } catch (Throwable error) {
                sentCheckFailed = true;
                RuntimeLog.log("streak sent check unavailable: " + error.getClass().getSimpleName() + " " + error.getMessage());
            }
        }
        StreakSentCheck check = sentCheck;
        return check == null ? StreakSentCheck.Answer.UNKNOWN : check.check(account, candidate.conversation, candidate.before);
    }

    private static List<StreakApi.Candidate> candidates(List<?> items, String account) throws Exception {
        return api.candidates(items, account, System.currentTimeMillis());
    }
}
