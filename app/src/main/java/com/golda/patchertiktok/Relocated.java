package com.golda.patchertiktok;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TikTok moves classes between packages when it reorganises its code, but usually keeps their
 * names. When a readable class the module relies on is not where it was, it is looked up by its
 * simple name in TikTok's own code, and the closest match by package is used. Results are kept in
 * a small file per TikTok build, so the dex scan happens once after an update.
 */
final class Relocated {
    private static final String NONE = "-";
    private static final Map<String, String> FOUND = new ConcurrentHashMap<>();
    private static DexFinder finder;
    private static File store;
    private static String build;

    private Relocated() { }

    static Class<?> find(String name, ClassLoader loader) {
        if (loader == null || name == null || !(name.startsWith("com.ss.") || name.startsWith("com.bytedance."))) return null;
        String moved = FOUND.get(name);
        if (moved == null) moved = locate(name, loader);
        if (NONE.equals(moved)) return null;
        try {
            return Class.forName(moved, false, loader);
        } catch (Throwable unusable) {
            return null;
        }
    }

    private static synchronized String locate(String name, ClassLoader loader) {
        String known = FOUND.get(name);
        if (known != null) return known;
        load(loader);
        known = FOUND.get(name);
        if (known != null) return known;
        String result = NONE;
        String simple = name.substring(name.lastIndexOf('.') + 1);
        // Short or generic names would match unrelated classes.
        if (simple.length() >= 8) {
            try {
                result = closest(name, finder(loader).classesNamed(simple), loader);
            } catch (Throwable error) {
                result = NONE;
            }
        }
        FOUND.put(name, result);
        save(name, result);
        if (!NONE.equals(result)) RuntimeLog.log("class moved: " + name + " -> " + result);
        return result;
    }

    /** The candidate sharing the longest package prefix with the old name; none if that is a tie. */
    private static String closest(String name, List<String> candidates, ClassLoader loader) {
        String best = NONE;
        // At least the first three package parts (com.ss.android, com.bytedance.x) must match.
        int minimum = 0;
        for (int dots = 0; minimum < name.length() && dots < 3; minimum++) if (name.charAt(minimum) == '.') dots++;
        int bestScore = minimum - 1;
        boolean tie = false;
        for (String candidate : candidates) {
            if (candidate.equals(name)) continue;
            try {
                Class.forName(candidate, false, loader);
            } catch (Throwable unusable) {
                continue;
            }
            int score = 0;
            int limit = Math.min(name.length(), candidate.length());
            while (score < limit && name.charAt(score) == candidate.charAt(score)) score++;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        return tie ? NONE : best;
    }

    private static DexFinder finder(ClassLoader loader) {
        if (finder == null) {
            // Shares Discovery's scratch folder, which is emptied once startup is over.
            File scratch = store == null ? null : new File(store.getParentFile().getParentFile(), "cache/tpx_dex");
            finder = new DexFinder(new ArrayList<>(codePaths(loader)), scratch);
        }
        return finder;
    }

    /** The APK and dex files a class loader reads, from its public description. */
    static Set<String> codePaths(ClassLoader loader) {
        Set<String> paths = new LinkedHashSet<>();
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            Matcher matcher = Pattern.compile("(?:zip file|dex file) \"([^\"]+)\"").matcher(String.valueOf(current));
            while (matcher.find()) {
                String path = matcher.group(1);
                if (!path.contains("split_config.") && new File(path).isFile()) paths.add(path);
            }
        }
        return paths;
    }

    /** Releases the mapped dex files once startup is over. */
    static synchronized void release() {
        finder = null;
    }

    // ---- per-build store -----------------------------------------------------------------

    private static void load(ClassLoader loader) {
        if (store != null) return;
        Set<String> paths = codePaths(loader);
        if (paths.isEmpty()) return;
        File apk = new File(paths.iterator().next());
        build = apk.getPath() + ":" + apk.lastModified();
        String packageName = packageOf(apk);
        if (packageName == null) return;
        store = new File("/data/data/" + packageName + "/files/tpx_relocated.txt");
        try {
            if (!store.isFile()) return;
            List<String> lines = java.nio.file.Files.readAllLines(store.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !build.equals(lines.get(0))) return;
            for (String line : lines.subList(1, lines.size())) {
                int split = line.indexOf('=');
                if (split > 0) FOUND.putIfAbsent(line.substring(0, split), line.substring(split + 1));
            }
        } catch (Throwable unreadable) {
            // Looked up again.
        }
    }

    private static void save(String name, String result) {
        if (store == null) return;
        try {
            File folder = store.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return;
            StringBuilder text = new StringBuilder(build).append('\n');
            for (Map.Entry<String, String> entry : FOUND.entrySet()) {
                text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            java.nio.file.Files.write(store.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable unwritable) {
            // Kept for this run only.
        }
    }

    /** The package name from an installed APK path (/data/app/~~x/<package>-y/base.apk). */
    private static String packageOf(File apk) {
        File folder = apk.getParentFile();
        if (folder == null) return null;
        String name = folder.getName();
        int dash = name.indexOf('-');
        return dash > 0 ? name.substring(0, dash) : null;
    }
}
