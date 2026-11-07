/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicqrscanner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PayloadTextTest {
    @Test
    public void ordinaryTextIsUnchanged() {
        String text = "WIFI:S:Casa;T:WPA;P:clave segura;;\nLínea 2\tñ 😀";
        assertFalse(PayloadText.containsHiddenCharacters(text));
        assertEquals(text, PayloadText.revealHiddenCharacters(text));
    }

    @Test
    public void bidiAndInvisibleCharactersBecomeVisibleMarkers() {
        String text = "invoice‮fdp.exe​ x";
        assertTrue(PayloadText.containsHiddenCharacters(text));
        assertEquals("invoice⟨U+202E⟩fdp.exe⟨U+200B⟩⟨U+00A0⟩x", PayloadText.revealHiddenCharacters(text));
    }

    @Test
    public void supplementaryInvisibleCharactersAreRevealed() {
        // U+E0041 TAG LATIN CAPITAL LETTER A is invisible and outside the BMP.
        String text = "a" + new String(Character.toChars(0xE0041)) + "b";
        assertEquals("a⟨U+E0041⟩b", PayloadText.revealHiddenCharacters(text));
    }

    @Test
    public void truncateDoesNotSplitSurrogatePairs() {
        String text = "ab😀cd";
        assertEquals("ab…", PayloadText.truncate(text, 3));
        assertEquals("ab😀…", PayloadText.truncate(text, 4));
        assertEquals(text, PayloadText.truncate(text, 10));
    }
}
