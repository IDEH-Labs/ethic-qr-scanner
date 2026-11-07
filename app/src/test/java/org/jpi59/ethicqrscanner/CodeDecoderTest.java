/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicqrscanner;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.nio.ByteBuffer;

import org.junit.Test;

public final class CodeDecoderTest {
    @Test
    public void decodesQrCodeInEveryOrientation() throws Exception {
        String expected = "https://example.org/ethic-qr-scanner-test";
        byte[] luma = render(BarcodeFormat.QR_CODE, expected, 300, 300);
        int width = 300;
        int height = 300;
        for (int turn = 0; turn < 4; turn++) {
            assertEquals(expected, CodeDecoder.decode(luma, width, height));
            luma = CodeDecoder.rotateClockwise(luma, width, height);
            int swap = width; width = height; height = swap;
        }
    }

    @Test
    public void decodesLinearBarcodeHeldVerticallyInFrame() throws Exception {
        // A camera sensor is usually landscape while the phone is held upright,
        // so a horizontal barcode reaches the decoder turned by a quarter.
        String expected = "ETHIC-128-0042";
        byte[] upright = render(BarcodeFormat.CODE_128, expected, 400, 120);
        assertEquals(expected, CodeDecoder.decode(upright, 400, 120));
        byte[] turned = CodeDecoder.rotateClockwise(upright, 400, 120);
        assertEquals(expected, CodeDecoder.decode(turned, 120, 400));
    }

    @Test
    public void returnsNullForImageWithoutCode() {
        byte[] blank = new byte[200 * 200];
        java.util.Arrays.fill(blank, (byte) 0xFF);
        assertNull(CodeDecoder.decode(blank, 200, 200));
    }

    @Test
    public void rotateClockwiseMovesPixelsCorrectly() {
        // 3x2 image:  1 2 3     rotated (2x3):  4 1
        //             4 5 6                     5 2
        //                                       6 3
        byte[] rotated = CodeDecoder.rotateClockwise(new byte[] {1, 2, 3, 4, 5, 6}, 3, 2);
        assertArrayEquals(new byte[] {4, 1, 5, 2, 6, 3}, rotated);
    }

    @Test
    public void compactPlaneDropsRowPaddingIncludingShortLastRow() {
        // Width 3, row stride 5; the last row omits its padding, as some devices do.
        ByteBuffer plane = ByteBuffer.wrap(new byte[] {1, 2, 3, 9, 9, 4, 5, 6});
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, CodeDecoder.compactPlane(plane, 5, 3, 2));
        assertEquals(0, plane.position());
    }

    @Test
    public void lumaFromArgbUsesZxingWeighting() {
        byte[] luma = CodeDecoder.lumaFromArgb(new int[] {0xFFFFFFFF, 0xFF000000, 0xFF00FF00});
        assertEquals((byte) 255, luma[0]);
        assertEquals(0, luma[1]);
        assertEquals((byte) 127, luma[2]);
    }

    @Test
    public void sampleSizeNeverDropsBelowTarget() {
        assertEquals(1, CodeDecoder.sampleSizeFor(1600, 1200, 1600));
        assertEquals(1, CodeDecoder.sampleSizeFor(3199, 2000, 1600));
        assertEquals(2, CodeDecoder.sampleSizeFor(3200, 2400, 1600));
        assertEquals(4, CodeDecoder.sampleSizeFor(4000, 8000, 1600));
        assertEquals(1, CodeDecoder.sampleSizeFor(10, 10, 1600));
    }

    private static byte[] render(BarcodeFormat format, String text, int width, int height) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(text, format, width, height);
        byte[] luma = new byte[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                luma[y * width + x] = (byte) (matrix.get(x, y) ? 0 : 0xFF);
            }
        }
        return luma;
    }
}
