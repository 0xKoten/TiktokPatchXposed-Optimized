package com.golda.patchertiktok;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CommentCopyTest {
    @Test
    public void usesExactModelTextWithKnownAuthor() {
        String body = "  Привет 👋\nAlice: still part of the comment  ";
        assertEquals(body, CommentCopy.textOnly("Alice: " + body, body, "Alice"));
        assertEquals(body, CommentCopy.textOnly("Анна：" + body, body, "Анна"));
        assertEquals(body, CommentCopy.textOnly("@alice_1\r\n" + body, body, "alice_1"));
        assertEquals(body, CommentCopy.textOnly("Alice:\n" + body, body, "Alice"));
        assertEquals(body, CommentCopy.textOnly("Alice:\t  " + body, body, "Alice"));
        assertEquals(body, CommentCopy.textOnly("Alice:" + body, body, "Alice"));
    }

    @Test
    public void preservesBodyAndMismatchedModels() {
        String body = "@alice: this is the actual comment";
        assertEquals(body, CommentCopy.textOnly(body, body, "alice"));
        assertEquals("Alice: another comment", CommentCopy.textOnly("Alice: another comment", "hello", "Alice"));
        assertEquals("Alice Jr: hello", CommentCopy.textOnly("Alice Jr: hello", "hello", "Alice"));
        assertEquals("Alice: hello", CommentCopy.textOnly("Alice: hello", "hello", "Bob"));
    }

    @Test
    public void tiktokFormatWithSpacesAndSymbolsInNickname() {
        // As copied by TikTok 47.1.4: "@nickname:text", no space after the colon.
        assertEquals("и в правду очень давно",
                CommentCopy.textOnly("@чешка #KLD:и в правду очень давно", "и в правду очень давно", "чешка #KLD"));
        assertEquals("ok", CommentCopy.textOnly("@Ա🇦🇲†:ok", "ok", "Ա🇦🇲†"));
    }

    @Test
    public void cleanLinksAndAuthorRemovalComposeInEitherOrder() {
        String body = "Look https://www.tiktok.com/@user/video/123?_r=1&share_uid=2";
        String clean = "Look https://www.tiktok.com/@user/video/123";
        assertEquals(clean, CleanLinks.cleanText(CommentCopy.textOnly("Alice: " + body, body, "Alice")));
        assertEquals(clean, CommentCopy.textOnly(CleanLinks.cleanText("Alice: " + body),
                CleanLinks.cleanText(body), "Alice"));
    }
}
