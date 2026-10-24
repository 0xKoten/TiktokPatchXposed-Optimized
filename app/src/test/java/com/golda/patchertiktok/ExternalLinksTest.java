package com.golda.patchertiktok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExternalLinksTest {
    @Test
    public void acceptsExternalHttpAndHttpsUrls() {
        for (String url : new String[]{"https://example.com/path?q=1#section", "http://example.org:8080/",
                "HTTPS://EXAMPLE.COM", "https://xn--e1afmkfd.xn--p1ai/", "http://[::1]:8080/"}) {
            assertTrue(url, ExternalLinks.isExternalUrl(url));
            assertEquals(url, ExternalLinks.browserUrl(url));
        }
    }

    @Test
    public void retainsTikTokPagesAndInfrastructure() {
        for (String host : new String[]{"tiktok.com", "tiktokv.com", "tiktokv.eu", "tiktokv.us",
                "tiktokw.eu", "tiktokw.us", "tiktokv-eu.com", "tiktokv-us.com", "tiktokcdn.com",
                "tiktokcdn.eu", "tiktokcdn-us.com", "tiktokcdn-eu.com", "musical.ly", "byteoversea.com",
                "byteoversea.net", "bytedance.com", "bytedance.net", "tiktok.shop", "tiktokshop.com",
                "tiktokglobalshop.com", "tiktok-row.net", "byteintl.com", "byteintlapi.com", "ttapis.com",
                "ttwstatic.com", "ttlstatic.com", "oecstatic.com", "ibytedtos.com", "ibyteimg.com"}) {
            assertFalse(host, ExternalLinks.isExternalUrl("https://" + host + "/"));
            assertFalse(host, ExternalLinks.isExternalUrl("https://sub." + host.toUpperCase() + ".:443/path"));
        }
        assertFalse(ExternalLinks.isExternalUrl("https://www.tiktok.com/?redirect=https://example.com"));
    }

    @Test
    public void classifiesHostInsteadOfSubstringOrUserInfo() {
        assertTrue(ExternalLinks.isExternalUrl("https://tiktok.com.example.org/"));
        assertTrue(ExternalLinks.isExternalUrl("https://not-tiktok.com/"));
        assertTrue(ExternalLinks.isExternalUrl("https://byteoversea.example.org/"));
        assertTrue(ExternalLinks.isExternalUrl("https://tiktok.com@example.org/"));
        assertFalse(ExternalLinks.isExternalUrl("https://example.org@tiktok.com/"));
        assertTrue(ExternalLinks.isExternalUrl("https://example.org/tiktok.com?url=https://www.tiktok.com"));
    }

    @Test
    public void leavesDeepLinksAndMalformedUrlsAlone() {
        for (String value : new String[]{"aweme://user/profile/123", "snssdk1233://video/123", "mailto:a@example.org",
                "javascript:alert(1)", "ftp://example.com", "//example.com", "/relative", "https://",
                "https:///example.com", "https://example.com:999999/", "https://exa mple.com", "", "https://%zz/"}) {
            assertFalse(value, ExternalLinks.isExternalUrl(value));
            assertNull(value, ExternalLinks.browserUrl(value));
        }
        assertFalse(ExternalLinks.isExternalUrl(null));
        assertNull(ExternalLinks.browserUrl(null));
    }

    @Test
    public void unwrapsOnlyBrowserRouteWithoutDoubleDecoding() {
        assertEquals("https://example.com/a?q=one+two&next=%2F",
                ExternalLinks.browserUrl("aweme://webview/?url=https%3A%2F%2Fexample.com%2Fa%3Fq%3Done%2Btwo%26next%3D%252F&use_spark=1"));
        assertEquals("https://example.com/?q=one+two",
                ExternalLinks.browserUrl("aweme://webview/?url=https://example.com/?q=one+two&use_spark=1"));
        assertEquals("https://www.tiktok.com/", ExternalLinks.browserUrl("aweme://webview/?url=https%3A%2F%2Fwww.tiktok.com%2F"));
        for (String value : new String[]{"aweme://user/profile/?url=https%3A%2F%2Fexample.com",
                "aweme://webview/unrelated?url=https%3A%2F%2Fexample.com", "aweme://webview/?url=javascript%3Aalert(1)",
                "aweme://webview/?url=%zz", "aweme://webview/?title=https://example.com"}) {
            assertNull(value, ExternalLinks.browserUrl(value));
        }
    }
}
