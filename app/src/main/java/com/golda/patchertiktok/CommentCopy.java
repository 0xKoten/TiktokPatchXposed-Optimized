package com.golda.patchertiktok;

import android.content.ClipData;
import android.content.ClipboardManager;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * TikTok's "Copy" on a comment puts "@nickname:text" into the clipboard. The comments on screen
 * are remembered as they are shown (Comment.getText()); a copied text that is exactly one of them
 * behind its author's name loses the name. Anything else copied in TikTok is left alone.
 */
final class CommentCopy {
    private static final String COMMENT = "com.ss.android.ugc.aweme.comment.model.Comment";
    private static final Pattern AUTHOR_SEPARATOR = Pattern.compile("(?:[:：][ \\t]*(?:\\r?\\n)?|\\r?\\n)");
    private static final Deque<Text> RECENT = new ArrayDeque<>();

    private CommentCopy() { }

    static void install(ClassLoader loader) {
        Class<?> comment = Hooks.findClass(COMMENT, loader);
        Method getter = comment == null ? null : Reflect.method(comment, "getText");
        if (getter == null || getter.getReturnType() != String.class) {
            RuntimeLog.log("comment copy unavailable: comment model not found");
            return;
        }
        Hooks.hook(getter, new Hooks.Hook() {
            @Override protected void after(Hooks.Call param) {
                if (!Prefs.on(Prefs.COPY_COMMENT_TEXT) || !(param.getResult() instanceof String)) return;
                remember((String) param.getResult(), param.thisObject);
            }
        });
        int clipboard = Hooks.hookAll(ClipboardManager.class, "setPrimaryClip", new Hooks.Hook() {
            @Override protected void before(Hooks.Call param) {
                if (!Prefs.on(Prefs.COPY_COMMENT_TEXT) || param.args.length == 0
                        || !(param.args[0] instanceof ClipData)) return;
                ClipData clip = (ClipData) param.args[0];
                if (clip.getItemCount() != 1) return;
                ClipData.Item item = clip.getItemAt(0);
                if (item.getText() == null || item.getUri() != null || item.getIntent() != null) return;
                String copied = item.getText().toString();
                String text = clean(copied);
                if (!copied.equals(text)) param.args[0] = new ClipData(clip.getDescription(), new ClipData.Item(text));
            }
        });
        RuntimeLog.log("comment copy: clipboard=" + clipboard);
    }

    /** Called for every comment TikTok draws, so the common case returns right away. */
    private static void remember(String body, Object comment) {
        synchronized (RECENT) {
            Text first = RECENT.peekFirst();
            if (first != null && first.body.equals(body)) return;
        }
        Object user = Reflect.call(comment, "getUser");
        Text text = new Text(body, string(Reflect.call(user, "getNickname")), string(Reflect.call(user, "getUniqueId")));
        synchronized (RECENT) {
            RECENT.removeIf(previous -> previous.body.equals(body));
            RECENT.addFirst(text);
            if (RECENT.size() > 64) RECENT.removeLast();
        }
    }

    private static String clean(String copied) {
        boolean links = Prefs.on(Prefs.CLEAN_LINKS);
        synchronized (RECENT) {
            for (Text text : RECENT) {
                // A comment that itself starts like "@name: ..." is copied as it is.
                if (copied.equals(text.body)) return copied;
            }
            for (Text text : RECENT) {
                String body = links ? CleanLinks.cleanText(text.body) : text.body;
                for (String author : new String[]{text.nickname, text.handle}) {
                    if (!copied.equals(textOnly(copied, text.body, author))
                            || !copied.equals(textOnly(copied, body, author))) return body;
                }
            }
        }
        return copied;
    }

    /** The comment's own text when {@code copied} is exactly its author's name followed by it. */
    static String textOnly(String copied, String body, String author) {
        if (copied == null || body == null || body.isEmpty() || copied.equals(body)
                || !copied.endsWith(body) || author == null) return copied;
        String name = author.trim();
        if (name.isEmpty()) return copied;
        String head = copied.substring(0, copied.length() - body.length());
        for (String prefix : new String[]{name, name.startsWith("@") ? name : "@" + name}) {
            if (head.startsWith(prefix) && AUTHOR_SEPARATOR.matcher(head.substring(prefix.length())).matches()) return body;
        }
        return copied;
    }

    private static String string(Object value) { return value instanceof String ? (String) value : null; }

    private static final class Text {
        final String body;
        final String nickname;
        final String handle;

        Text(String body, String nickname, String handle) {
            this.body = body;
            this.nickname = nickname;
            this.handle = handle;
        }
    }
}
