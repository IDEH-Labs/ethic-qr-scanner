/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicqrscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LinkSafetyTest {
    @Test
    public void ordinaryHttpsLinkIsOfferedWithoutConcerns() {
        LinkSafety.Review review = LinkSafety.review("https://Example.org/path?q=1#top");
        assertNotNull(review);
        assertEquals("https://Example.org/path?q=1#top", review.address);
        assertEquals("example.org", review.asciiHost);
        assertTrue(review.concerns.isEmpty());
    }

    @Test
    public void trailingNewlineFromGeneratorIsTrimmed() {
        LinkSafety.Review review = LinkSafety.review("https://example.org/\n");
        assertNotNull(review);
        assertEquals("https://example.org/", review.address);
    }

    @Test
    public void plainHttpIsFlagged() {
        LinkSafety.Review review = LinkSafety.review("http://example.org");
        assertNotNull(review);
        assertTrue(review.concerns.contains(LinkSafety.Concern.PLAIN_HTTP));
    }

    @Test
    public void textBeforeAtDoesNotHideTheRealHost() {
        LinkSafety.Review review = LinkSafety.review("https://bank.example@evil.test/login");
        assertNotNull(review);
        assertEquals("evil.test", review.asciiHost);
        assertTrue(review.concerns.contains(LinkSafety.Concern.TEXT_BEFORE_AT));
    }

    @Test
    public void lastAtSignSeparatesTheHostLikeBrowsers() {
        LinkSafety.Review review = LinkSafety.review("https://a@good.example@evil.test/");
        assertNotNull(review);
        assertEquals("evil.test", review.asciiHost);
    }

    @Test
    public void lookAlikeInternationalHostIsShownInAsciiForm() {
        // "аpple.com" starting with Cyrillic U+0430.
        LinkSafety.Review review = LinkSafety.review("https://аpple.com/");
        assertNotNull(review);
        assertEquals("xn--pple-43d.com", review.asciiHost);
        assertEquals("аpple.com", review.displayHost);
        assertTrue(review.concerns.contains(LinkSafety.Concern.INTERNATIONAL_HOST));
    }

    @Test
    public void punycodeHostIsFlaggedAndDecodedForReading() {
        LinkSafety.Review review = LinkSafety.review("https://xn--pple-43d.com/");
        assertNotNull(review);
        assertEquals("аpple.com", review.displayHost);
        assertTrue(review.concerns.contains(LinkSafety.Concern.INTERNATIONAL_HOST));
    }

    @Test
    public void numericHostsAreFlagged() {
        assertTrue(LinkSafety.review("http://192.168.1.1/").concerns.contains(LinkSafety.Concern.NUMERIC_HOST));
        assertTrue(LinkSafety.review("http://0x7f.1/").concerns.contains(LinkSafety.Concern.NUMERIC_HOST));
        assertTrue(LinkSafety.review("http://2130706433/").concerns.contains(LinkSafety.Concern.NUMERIC_HOST));
        assertTrue(LinkSafety.review("https://[::1]:8443/").concerns.contains(LinkSafety.Concern.NUMERIC_HOST));
        assertFalse(LinkSafety.review("https://1.example.org/").concerns.contains(LinkSafety.Concern.NUMERIC_HOST));
    }

    @Test
    public void explicitPortIsAcceptedAndExcludedFromHost() {
        LinkSafety.Review review = LinkSafety.review("https://example.org:8443/x");
        assertNotNull(review);
        assertEquals("example.org", review.asciiHost);
    }

    @Test
    public void bidiOverrideMakesLinkCopyOnly() {
        assertNull(LinkSafety.review("https://example.org/‮gpj.exe"));
    }

    @Test
    public void zeroWidthCharacterMakesLinkCopyOnly() {
        assertNull(LinkSafety.review("https://exa​mple.org/"));
    }

    @Test
    public void backslashMakesLinkCopyOnly() {
        // Browsers treat "\" as "/", so the host would end before "@".
        assertNull(LinkSafety.review("https://evil.test\\@good.example/"));
    }

    @Test
    public void innerWhitespaceMakesLinkCopyOnly() {
        assertNull(LinkSafety.review("https://example.org/a b"));
        assertNull(LinkSafety.review("https://example.org/a b"));
    }

    @Test
    public void percentEncodedHostMakesLinkCopyOnly() {
        assertNull(LinkSafety.review("https://%65vil.test/"));
    }

    @Test
    public void ambiguousIdnaLettersMakeLinkCopyOnly() {
        assertNull(LinkSafety.review("https://straße.de/"));
    }

    @Test
    public void invalidOrUnsupportedValuesAreNotOffered() {
        assertNull(LinkSafety.review(null));
        assertNull(LinkSafety.review(""));
        assertNull(LinkSafety.review("javascript:alert(1)"));
        assertNull(LinkSafety.review("intent://scan/#Intent;scheme=zxing;end"));
        assertNull(LinkSafety.review("ftp://example.org/"));
        assertNull(LinkSafety.review("https:///path"));
        assertNull(LinkSafety.review("https://example.org:99999/"));
        assertNull(LinkSafety.review("https://example.org:80a/"));
        assertNull(LinkSafety.review("https://exa<mple.org/"));
        assertNull(LinkSafety.review("https://a..b/"));
        assertNull(LinkSafety.review("https://[::1/"));
    }

    @Test
    public void lengthLimitIsInclusive() {
        String prefix = "https://example.org/";
        String atLimit = prefix + repeat('a', LinkSafety.MAX_LINK_LENGTH - prefix.length());
        assertNotNull(LinkSafety.review(atLimit));
        assertNull(LinkSafety.review(atLimit + "a"));
    }

    @Test
    public void unicodePathIsAllowed() {
        LinkSafety.Review review = LinkSafety.review("https://es.wikipedia.org/wiki/España");
        assertNotNull(review);
        assertTrue(review.concerns.isEmpty());
    }

    @Test
    public void looksLikeWebLinkIgnoresCaseAndEdges() {
        assertTrue(LinkSafety.looksLikeWebLink("  HTTPS://x"));
        assertFalse(LinkSafety.looksLikeWebLink("mailto:a@example.org"));
    }

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }
}
