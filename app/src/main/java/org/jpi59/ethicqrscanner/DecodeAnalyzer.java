package org.jpi59.ethicqrscanner;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;

import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes the luminance plane locally; no image is written to storage or transmitted. */
final class DecodeAnalyzer implements ImageAnalysis.Analyzer {
    interface Callback { void onDecoded(@NonNull String content); }

    private final Callback callback;
    private final AtomicBoolean paused = new AtomicBoolean(false);

    DecodeAnalyzer(Callback callback) {
        this.callback = callback;
    }

    void pause() { paused.set(true); }
    void resume() { paused.set(false); }

    @Override
    public void analyze(@NonNull ImageProxy image) {
        try {
            if (paused.get()) return;
            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            int width = image.getWidth();
            int height = image.getHeight();
            byte[] luma = CodeDecoder.compactPlane(plane.getBuffer(), plane.getRowStride(), width, height);
            // Both orientations are always tried, so the result does not depend
            // on how the phone is held relative to the camera sensor.
            String text = CodeDecoder.decode(luma, width, height);
            if (text != null && paused.compareAndSet(false, true)) {
                callback.onDecoded(text);
            }
        } catch (RuntimeException unexpected) {
            // A malformed frame must not stop the scanner; the next frame is tried.
        } finally {
            image.close();
        }
    }
}
