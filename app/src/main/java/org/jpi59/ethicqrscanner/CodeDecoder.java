/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicqrscanner;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.Binarizer;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Local, platform-independent decoding of a grayscale (luminance) image.
 * It has no Android dependency so that it can be verified with plain JVM tests.
 */
final class CodeDecoder {
    private static final Set<BarcodeFormat> LINEAR_FORMATS = EnumSet.of(
            BarcodeFormat.PDF_417, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39,
            BarcodeFormat.CODE_93, BarcodeFormat.EAN_13, BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.ITF);
    private static final Set<BarcodeFormat> ALL_FORMATS;

    static {
        Set<BarcodeFormat> all = EnumSet.of(
                BarcodeFormat.QR_CODE, BarcodeFormat.AZTEC, BarcodeFormat.DATA_MATRIX);
        all.addAll(LINEAR_FORMATS);
        ALL_FORMATS = Collections.unmodifiableSet(all);
    }

    private static final Map<DecodeHintType, Object> ALL_HINTS = hints(ALL_FORMATS);
    // QR, Aztec and Data Matrix detection is orientation-independent. Linear
    // barcodes are read along rows, so they need a second, quarter-turned pass.
    private static final Map<DecodeHintType, Object> LINEAR_HINTS = hints(LINEAR_FORMATS);

    private CodeDecoder() { }

    /**
     * Returns the decoded text, or {@code null} when no supported code is found.
     * {@code luma} must hold exactly {@code width * height} bytes, row by row.
     */
    static String decode(byte[] luma, int width, int height) {
        PlanarYUVLuminanceSource upright = source(luma, width, height);
        Result result = read(new HybridBinarizer(upright), ALL_HINTS);
        if (result == null) result = read(new GlobalHistogramBinarizer(upright), ALL_HINTS);
        if (result == null) {
            // ZXing's planar source cannot rotate itself, so rotate the plane here.
            PlanarYUVLuminanceSource turned = source(rotateClockwise(luma, width, height), height, width);
            result = read(new HybridBinarizer(turned), LINEAR_HINTS);
        }
        return result == null ? null : result.getText();
    }

    /** Copies a camera plane, which may contain row padding, into a compact array. */
    static byte[] compactPlane(ByteBuffer plane, int rowStride, int width, int height) {
        byte[] compact = new byte[width * height];
        ByteBuffer source = plane.duplicate();
        int start = source.position();
        for (int row = 0; row < height; row++) {
            source.position(start + row * rowStride);
            source.get(compact, row * width, width);
        }
        return compact;
    }

    static byte[] rotateClockwise(byte[] luma, int width, int height) {
        byte[] rotated = new byte[luma.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                rotated[x * height + (height - 1 - y)] = luma[y * width + x];
            }
        }
        return rotated;
    }

    /** Converts ARGB pixels to luminance using the same weighting as ZXing's RGB source. */
    static byte[] lumaFromArgb(int[] argb) {
        byte[] luma = new byte[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            int r = (pixel >> 16) & 0xFF;
            int g = (pixel >> 8) & 0xFF;
            int b = pixel & 0xFF;
            luma[i] = (byte) ((r + 2 * g + b) / 4);
        }
        return luma;
    }

    /**
     * Largest power-of-two subsampling that still leaves the longest side at
     * or above {@code targetSide}, so a large photo is never decoded at full size.
     */
    static int sampleSizeFor(int width, int height, int targetSide) {
        int longest = Math.max(width, height);
        int sample = 1;
        while (longest / (sample * 2) >= targetSide) sample *= 2;
        return sample;
    }

    private static PlanarYUVLuminanceSource source(byte[] luma, int width, int height) {
        return new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false);
    }

    private static Result read(Binarizer binarizer, Map<DecodeHintType, Object> hints) {
        try {
            return new MultiFormatReader().decode(new BinaryBitmap(binarizer), hints);
        } catch (Exception notFound) {
            // Most frames contain no code; this is the expected outcome.
            return null;
        }
    }

    private static Map<DecodeHintType, Object> hints(Set<BarcodeFormat> formats) {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return Collections.unmodifiableMap(hints);
    }
}
