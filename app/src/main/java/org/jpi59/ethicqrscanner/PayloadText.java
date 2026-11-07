/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicqrscanner;

import java.util.Locale;

/**
 * Makes decoded text safe to show. A payload is untrusted input: invisible or
 * text-direction control characters could make the screen show something other
 * than what is actually copied or opened, so they are rendered as visible markers.
 */
final class PayloadText {
    private PayloadText() { }

    /**
     * True for characters that are invisible, reorder surrounding text, or are
     * easily mistaken for an ordinary space. Tab, newline and carriage return are
     * ordinary text layout and are not included.
     */
    static boolean isHiddenCharacter(int codePoint) {
        if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r') return false;
        switch (Character.getType(codePoint)) {
            case Character.CONTROL:
            case Character.FORMAT:              // bidi controls, zero-width characters, BOM, tags
            case Character.LINE_SEPARATOR:
            case Character.PARAGRAPH_SEPARATOR:
            case Character.SURROGATE:           // an unpaired half of a surrogate pair
                return true;
            case Character.SPACE_SEPARATOR:
                return codePoint != ' ';
            default:
                break;
        }
        switch (codePoint) {
            case 0x034F: // combining grapheme joiner
            case 0x115F: case 0x1160: case 0x3164: case 0xFFA0: // Hangul fillers render blank
            case 0x2800: // braille pattern blank
                return true;
            default:
                return (codePoint >= 0xFE00 && codePoint <= 0xFE0F)       // variation selectors
                        || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
        }
    }

    static boolean containsHiddenCharacters(String value) {
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (isHiddenCharacter(codePoint)) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    /** Replaces every hidden character with a visible marker such as ⟨U+202E⟩. */
    static String revealHiddenCharacters(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); ) {
            int codePoint = value.codePointAt(i);
            if (isHiddenCharacter(codePoint)) {
                out.append('⟨').append(String.format(Locale.ROOT, "U+%04X", codePoint)).append('⟩');
            } else {
                out.appendCodePoint(codePoint);
            }
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }

    /** Shortens long text without splitting a surrogate pair, marking the cut with an ellipsis. */
    static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        int end = maxChars;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }
}
