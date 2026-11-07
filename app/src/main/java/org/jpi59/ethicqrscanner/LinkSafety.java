/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicqrscanner;

import java.net.IDN;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a decoded payload may be offered for opening in a browser and
 * extracts what the person needs to review first: the host the browser will
 * actually contact, in the ASCII form browsers use, plus any feature that can
 * make a link look like it leads somewhere else.
 *
 * This is an offline, syntactic review. It cannot tell whether a website is
 * safe, and it cannot detect look-alike names written only with ASCII letters
 * (for example "paypa1.com").
 */
final class LinkSafety {
    /** Keeps a destination short enough to be reviewed completely before it is opened. */
    static final int MAX_LINK_LENGTH = 2048;

    enum Concern {
        /** The connection will not be encrypted. */
        PLAIN_HTTP,
        /** Text before "@" (user information) can impersonate the real host. */
        TEXT_BEFORE_AT,
        /** The host contains non-ASCII letters or ASCII-encoded (xn--) labels. */
        INTERNATIONAL_HOST,
        /** The host is a numeric address instead of a domain name. */
        NUMERIC_HOST
    }

    /** A link that may be offered for opening after the person reviews it. */
    static final class Review {
        /** The exact value that would be handed to the browser. */
        final String address;
        /** The host in the ASCII form used to connect, without port or user information. */
        final String asciiHost;
        /** The host as people would read it; equal to {@link #asciiHost} for ASCII-only hosts. */
        final String displayHost;
        final Set<Concern> concerns;

        Review(String address, String asciiHost, String displayHost, Set<Concern> concerns) {
            this.address = address;
            this.asciiHost = asciiHost;
            this.displayHost = displayHost;
            this.concerns = Collections.unmodifiableSet(concerns);
        }
    }

    private LinkSafety() { }

    /** True when the payload starts like a web link, whether or not it may be opened. */
    static boolean looksLikeWebLink(String payload) {
        String value = payload.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("http://") || value.startsWith("https://");
    }

    /**
     * Returns a review for an HTTP(S) link that can be shown faithfully, or
     * {@code null} when the payload must stay copy-only.
     */
    static Review review(String payload) {
        if (payload == null) return null;
        // trim() removes only ASCII spaces and control characters at the edges,
        // such as a trailing newline commonly added by QR generators.
        String address = payload.trim();
        if (address.isEmpty() || address.length() > MAX_LINK_LENGTH) return null;

        for (int i = 0; i < address.length(); ) {
            int codePoint = address.codePointAt(i);
            // Browsers silently drop or reinterpret these characters: a backslash
            // acts as "/" and can move the end of the host. None is needed in a
            // genuine link, so a link containing one cannot be shown faithfully.
            if (codePoint == '\\' || Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint) || PayloadText.isHiddenCharacter(codePoint)) {
                return null;
            }
            i += Character.charCount(codePoint);
        }

        int schemeEnd = address.indexOf("://");
        if (schemeEnd <= 0) return null;
        String scheme = address.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;

        Set<Concern> concerns = EnumSet.noneOf(Concern.class);
        if (scheme.equals("http")) concerns.add(Concern.PLAIN_HTTP);

        String rest = address.substring(schemeEnd + 3);
        int authorityEnd = rest.length();
        for (char delimiter : new char[] {'/', '?', '#'}) {
            int index = rest.indexOf(delimiter);
            if (index >= 0 && index < authorityEnd) authorityEnd = index;
        }
        String authority = rest.substring(0, authorityEnd);

        // Browsers use the last "@" to separate user information from the host.
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            concerns.add(Concern.TEXT_BEFORE_AT);
            authority = authority.substring(at + 1);
        }

        String host;
        String port;
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return null;
            host = authority.substring(0, close + 1).toLowerCase(Locale.ROOT);
            String afterHost = authority.substring(close + 1);
            if (!afterHost.isEmpty() && !afterHost.startsWith(":")) return null;
            port = afterHost.isEmpty() ? "" : afterHost.substring(1);
            if (!host.matches("\\[[0-9a-f:.]+\\]")) return null;
            concerns.add(Concern.NUMERIC_HOST);
            if (!validPort(port)) return null;
            return new Review(address, host, host, concerns);
        }
        int colon = authority.lastIndexOf(':');
        host = colon >= 0 ? authority.substring(0, colon) : authority;
        port = colon >= 0 ? authority.substring(colon + 1) : "";
        if (host.isEmpty() || !validPort(port)) return null;
        // Browsers percent-decode a host before connecting, so the text shown
        // would not be the host contacted.
        if (host.indexOf('%') >= 0) return null;
        // IDNA2003 (java.net.IDN) and the UTS #46 processing used by browsers
        // convert these letters differently. The ASCII host shown could then
        // differ from the one contacted, so such a link stays copy-only.
        if (host.indexOf('ß') >= 0 || host.indexOf('ς') >= 0) return null;

        String asciiHost;
        try {
            asciiHost = IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        if (asciiHost.endsWith(".")) asciiHost = asciiHost.substring(0, asciiHost.length() - 1);
        if (asciiHost.isEmpty() || !asciiHost.matches("[a-z0-9._-]+")) return null;
        for (String label : asciiHost.split("\\.", -1)) {
            if (label.isEmpty()) return null;
        }

        String displayHost = asciiHost;
        boolean international = !isAscii(host) || asciiHost.startsWith("xn--") || asciiHost.contains(".xn--");
        if (international) {
            concerns.add(Concern.INTERNATIONAL_HOST);
            displayHost = IDN.toUnicode(asciiHost);
        }
        if (isNumericHost(asciiHost)) concerns.add(Concern.NUMERIC_HOST);
        return new Review(address, asciiHost, displayHost, concerns);
    }

    private static boolean validPort(String port) {
        if (port.isEmpty()) return true;
        if (port.length() > 5 || !port.matches("[0-9]+")) return false;
        return Integer.parseInt(port) <= 65535;
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) return false;
        }
        return true;
    }

    /**
     * Browsers parse a host as an IPv4 address when its last label is numeric,
     * including hexadecimal ("0x7f") and octal forms.
     */
    private static boolean isNumericHost(String asciiHost) {
        String[] labels = asciiHost.split("\\.");
        String last = labels[labels.length - 1];
        return last.matches("[0-9]+") || last.matches("0x[0-9a-f]*");
    }
}
