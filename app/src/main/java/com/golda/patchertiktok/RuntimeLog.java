package com.golda.patchertiktok;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LSPosed drops module lines after the first dozen of a process, so every line also goes to
 * TikTok's cache/patcher.log (rewritten on each launch, capped at 256 KB).
 */
final class RuntimeLog {
    private static final long LIMIT = 256 * 1024;
    private static final List<String> EARLY = new ArrayList<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static File file;

    private RuntimeLog() { }

    static void log(String message) {
        FeatureStatus.observe(message);
        Hooks.log(message);
        write(message);
    }

    /** Called once the Application context exists; lines logged before that are written first. */
    static synchronized void attach(Context context) {
        if (file != null) return;
        try {
            File target = new File(context.getCacheDir(), "patcher.log");
            try (FileWriter out = new FileWriter(target, false)) {
                for (String line : EARLY) out.write(line);
            }
            file = target;
        } catch (Throwable ignored) {
            // No log file; LSPosed's log is still there.
        }
        EARLY.clear();
    }

    private static synchronized void write(String message) {
        String line = TIME.format(new Date()) + " " + message + "\n";
        if (file == null) {
            if (EARLY.size() < 200) EARLY.add(line);
            return;
        }
        if (file.length() > LIMIT) return;
        try (FileWriter out = new FileWriter(file, true)) {
            out.write(line);
        } catch (Throwable ignored) {
            // Best effort only.
        }
    }
}
