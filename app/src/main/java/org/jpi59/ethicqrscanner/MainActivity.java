package org.jpi59.ethicqrscanner;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.text.LineBreaker;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.text.Layout;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import android.app.AlertDialog;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * A local-only QR and barcode scanner. Decoded text is never sent to a service
 * and web links are never opened without an explicit, informed confirmation.
 */
public final class MainActivity extends androidx.activity.ComponentActivity {
    private static final int MAX_DISPLAYED_CHARS = 4000;
    private static final int MAX_PHOTO_SIDE = 1600;

    private final ActivityResultLauncher<String> cameraPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    showScanner();
                } else {
                    showPermissionMessage();
                }
            });
    private final ActivityResultLauncher<String> photoPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), this::decodePhoto);

    private FrameLayout root;
    private PreviewView previewView;
    private Preview preview;
    private ImageAnalysis analysis;
    private Camera camera;
    private DecodeAnalyzer decoder;
    private TorchButtonView torchButton;
    private PhotoButtonView photoButton;
    private AlertDialog activeDialog;
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(color(R.color.night));
        getWindow().setNavigationBarColor(color(R.color.night));
        // Scanning is the primary task: enter the camera directly. Android still
        // controls the permission prompt; the app never bypasses that consent.
        requestCameraForScanning();
    }

    /**
     * Rotation and window resizing are handled here (see android:configChanges)
     * so that a decoded result being reviewed is not lost. The decoded content
     * is deliberately never written to the saved-instance-state bundle.
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int rotation = displayRotation();
        if (preview != null) preview.setTargetRotation(rotation);
        if (analysis != null) analysis.setTargetRotation(rotation);
        if (activeDialog != null && activeDialog.isShowing()) fitDialogWidth(activeDialog);
    }

    private void showPermissionMessage() {
        track(new AlertDialog.Builder(this)
                .setTitle(R.string.permission_needed)
                .setMessage(R.string.camera_permission_explanation)
                .setNegativeButton(R.string.close, (dialog, which) -> finish())
                .setPositiveButton(R.string.retry_camera, (dialog, which) -> requestCameraForScanning())
                .setOnCancelListener(dialog -> finish())
                .show());
    }

    private void showTransparency() {
        track(new AlertDialog.Builder(this)
                .setTitle(R.string.transparency_dialog_title)
                .setMessage(R.string.transparency_dialog_message)
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    private void decodePhoto(Uri uri) {
        if (uri == null) return;
        // Stop live decoding so that a camera result cannot appear over the photo result.
        if (decoder != null) decoder.pause();
        Toast.makeText(this, getString(R.string.reading_photo), Toast.LENGTH_SHORT).show();
        analysisExecutor.execute(() -> {
            String decoded = null;
            boolean readable = false;
            try {
                Bitmap bitmap = loadBoundedBitmap(uri);
                if (bitmap != null) {
                    readable = true;
                    int width = bitmap.getWidth();
                    int height = bitmap.getHeight();
                    int[] pixels = new int[width * height];
                    bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                    bitmap.recycle();
                    decoded = CodeDecoder.decode(CodeDecoder.lumaFromArgb(pixels), width, height);
                }
            } catch (IOException | RuntimeException | OutOfMemoryError failure) {
                // Unreadable, revoked or oversized images are reported below. The
                // OutOfMemoryError is recoverable here: every reference to the
                // image is local to this block and is released when it exits.
                readable = false;
            }
            String result = decoded;
            boolean photoReadable = readable;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result != null && !result.isEmpty()) {
                    showResult(result);
                    return;
                }
                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle(photoReadable ? R.string.no_code_title : R.string.photo_unreadable_title)
                        .setMessage(photoReadable ? R.string.no_code_message : R.string.photo_unreadable_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
                dialog.setOnDismissListener(ignored -> resumeScanning());
                track(dialog);
                dialog.show();
            });
        });
    }

    /**
     * Decodes the picked image subsampled at load time, so a very large photo
     * is never held in memory at full resolution. The stream is always closed.
     */
    private Bitmap loadBoundedBitmap(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = CodeDecoder.sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_PHOTO_SIDE);
        Bitmap sampled;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            sampled = BitmapFactory.decodeStream(in, null, options);
        }
        if (sampled == null) return null;

        int width = sampled.getWidth();
        int height = sampled.getHeight();
        float scale = Math.min(1f, MAX_PHOTO_SIDE / (float) Math.max(width, height));
        if (scale >= 1f) return sampled;
        Bitmap scaled = Bitmap.createScaledBitmap(sampled,
                Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)), true);
        if (scaled != sampled) sampled.recycle();
        return scaled;
    }

    private void requestCameraForScanning() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            Toast.makeText(this, getString(R.string.camera_unavailable), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void showScanner() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new ScannerOverlayView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), dp(14), dp(20), dp(14));
        header.setBackgroundColor(0xCC0C1C20);
        ImageButton menu = new ImageButton(this);
        menu.setImageResource(R.drawable.ic_settings_menu);
        menu.setBackground(null);
        menu.setColorFilter(color(R.color.teal));
        menu.setContentDescription(getString(R.string.menu_title));
        menu.setOnClickListener(v -> EthicEcosystemMenu.show(this, true));
        LinearLayout.LayoutParams menuParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        menuParams.setMarginEnd(dp(4));
        header.addView(menu, menuParams);
        TextView brand = text(getString(R.string.scanner_brand), 15, Color.WHITE);
        brand.setLetterSpacing(0.1f);
        brand.setMaxLines(1);
        brand.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(brand, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button info = button("ⓘ", Color.TRANSPARENT, color(R.color.teal));
        info.setTextSize(22);
        info.setContentDescription(getString(R.string.transparency_dialog_title));
        info.setOnClickListener(v -> showTransparency());
        header.addView(info, new LinearLayout.LayoutParams(dp(48), dp(48)));
        Button close = button("×", Color.TRANSPARENT, Color.WHITE);
        close.setTextSize(30);
        close.setContentDescription(getString(R.string.close_camera));
        close.setPadding(dp(10), 0, dp(10), 0);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(48)));
        FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(header, headerParams);

        torchButton = new TorchButtonView(this);
        torchButton.setContentDescription(getString(R.string.toggle_torch));
        torchButton.setOnClickListener(v -> toggleTorch());
        FrameLayout.LayoutParams torchParams = new FrameLayout.LayoutParams(
                dp(64), dp(64), Gravity.LEFT | Gravity.TOP);
        root.addView(torchButton, torchParams);
        photoButton = new PhotoButtonView(this);
        photoButton.setContentDescription(getString(R.string.choose_photo));
        photoButton.setOnClickListener(v -> photoPicker.launch("image/*"));
        FrameLayout.LayoutParams photoParams = new FrameLayout.LayoutParams(
                dp(64), dp(64), Gravity.LEFT | Gravity.TOP);
        root.addView(photoButton, photoParams);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = right - left;
            int height = bottom - top;
            float frameSize = Math.min(width * 0.78f, height * 0.48f);
            int frameTop = Math.round((height - frameSize) * 0.45f);
            int groupLeft = (width - dp(144)) / 2;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) torchButton.getLayoutParams();
            params.topMargin = frameTop + Math.round(frameSize) + dp(16);
            params.leftMargin = groupLeft;
            torchButton.setLayoutParams(params);
            FrameLayout.LayoutParams photoLayout = (FrameLayout.LayoutParams) photoButton.getLayoutParams();
            photoLayout.topMargin = params.topMargin;
            photoLayout.leftMargin = groupLeft + dp(80);
            photoButton.setLayoutParams(photoLayout);
        });

        setContentView(root);
        bindCamera();
    }

    private void bindCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                int rotation = displayRotation();
                preview = new Preview.Builder().setTargetRotation(rotation).build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                decoder = new DecodeAnalyzer(content -> runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) showResult(content);
                }));
                analysis = new ImageAnalysis.Builder()
                        .setTargetRotation(rotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analysisExecutor, decoder);
                provider.unbindAll();
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                camera.getCameraInfo().getTorchState().observe(this, state -> {
                    if (torchButton != null) {
                        torchButton.setTorchEnabled(state != null && state == androidx.camera.core.TorchState.ON);
                    }
                });
            } catch (Exception error) {
                Toast.makeText(this, getString(R.string.camera_start_failed), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void showResult(@NonNull String content) {
        if (decoder != null) decoder.pause();
        if (camera != null) camera.getCameraControl().enableTorch(false);
        LinkSafety.Review link = LinkSafety.review(content);
        String classification;
        if (link != null) {
            classification = getString(R.string.link_detected_message);
        } else if (LinkSafety.looksLikeWebLink(content)) {
            classification = getString(R.string.link_not_openable_message);
        } else {
            classification = getString(R.string.content_detected_message);
        }
        if (PayloadText.containsHiddenCharacters(content)) {
            classification += "\n\n" + getString(R.string.hidden_characters_notice);
        }
        // Hidden characters are shown as markers; Copy still returns the exact payload.
        String displayed = PayloadText.truncate(PayloadText.revealHiddenCharacters(content), MAX_DISPLAYED_CHARS);
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        result.setPadding(dp(24), dp(22), dp(24), dp(18));
        GradientDrawable resultBackground = new GradientDrawable();
        resultBackground.setColor(color(R.color.night_elevated));
        resultBackground.setCornerRadius(dp(24));
        resultBackground.setStroke(dp(1), 0x4D8DE5D0);
        result.setBackground(resultBackground);

        TextView title = text(getString(R.string.content_detected_title), 24, Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, 0, 0, dp(10));
        result.addView(title);

        TextView explanation = text(classification, 16, color(R.color.mist));
        explanation.setPadding(0, 0, 0, dp(16));
        result.addView(explanation);

        LinearLayout valuePanel = new LinearLayout(this);
        valuePanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable valueBackground = new GradientDrawable();
        valueBackground.setColor(color(R.color.ink));
        valueBackground.setCornerRadius(dp(14));
        valuePanel.setBackground(valueBackground);
        TextView value = text(displayed, 17, Color.WHITE);
        value.setTextIsSelectable(true);
        value.setHorizontallyScrolling(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            value.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
            value.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL);
        }
        // Deliberately do not turn detected URLs into inline links. A QR payload
        // is untrusted input; opening it is only possible through the separate
        // review-and-confirm flow below.
        ResultScrollView scroll = new ResultScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(value, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        valuePanel.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        result.addView(valuePanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button primary = button(link != null ? getString(R.string.review_link) : getString(R.string.copy),
                color(R.color.teal), color(R.color.ink));
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        primaryParams.topMargin = dp(18);
        result.addView(primary, primaryParams);

        LinearLayout secondary = new LinearLayout(this);
        secondary.setOrientation(LinearLayout.VERTICAL);
        secondary.setGravity(Gravity.CENTER_HORIZONTAL);
        Button scanAgain = outlinedButton(getString(R.string.scan_again));
        secondary.addView(scanAgain, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        // A second Copy button is only needed when the primary action is the link review.
        Button copyButton = null;
        if (link != null) {
            copyButton = outlinedButton(getString(R.string.copy));
            LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            copyParams.topMargin = dp(8);
            secondary.addView(copyButton, copyParams);
        }
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secondaryParams.topMargin = dp(8);
        result.addView(secondary, secondaryParams);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(result).create();
        primary.setOnClickListener(v -> {
            dialog.dismiss();
            if (link != null) showBrowserConfirmation(link); else copy(content);
        });
        scanAgain.setOnClickListener(v -> { dialog.dismiss(); resumeScanning(); });
        if (copyButton != null) copyButton.setOnClickListener(v -> { dialog.dismiss(); copy(content); });
        dialog.setOnCancelListener(d -> resumeScanning());
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                fitDialogWidth(dialog);
            }
        });
        track(dialog);
        dialog.show();
    }

    private void fitDialogWidth(AlertDialog dialog) {
        if (dialog.getWindow() == null) return;
        int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.92f);
        dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void resumeScanning() {
        if (decoder != null) decoder.resume();
    }

    private void copy(String content) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), content));
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show();
        resumeScanning();
    }

    private void openInBrowser(String address) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address)));
        } catch (Exception noHandler) {
            Toast.makeText(this, getString(R.string.no_browser), Toast.LENGTH_LONG).show();
        }
        resumeScanning();
    }

    private void showBrowserConfirmation(LinkSafety.Review link) {
        // Force left-to-right isolation so right-to-left letters inside the
        // address cannot visually reorder it in the dialog.
        BidiFormatter bidi = BidiFormatter.getInstance();
        StringBuilder message = new StringBuilder(getString(R.string.link_host_label,
                bidi.unicodeWrap(link.asciiHost, TextDirectionHeuristics.LTR)));
        if (link.concerns.contains(LinkSafety.Concern.INTERNATIONAL_HOST)) {
            message.append('\n').append(getString(R.string.link_written_as,
                    bidi.unicodeWrap(link.displayHost, TextDirectionHeuristics.LTR)));
        }
        for (LinkSafety.Concern concern : link.concerns) {
            message.append("\n\n⚠ ").append(getString(concernText(concern)));
        }
        message.append("\n\n").append(getString(R.string.open_link_message,
                bidi.unicodeWrap(link.address, TextDirectionHeuristics.LTR)));
        track(new AlertDialog.Builder(this)
                .setTitle(R.string.open_link_title)
                .setMessage(message.toString())
                .setNegativeButton(R.string.cancel, (dialog, which) -> resumeScanning())
                .setPositiveButton(R.string.open_browser, (dialog, which) -> openInBrowser(link.address))
                .setOnCancelListener(dialog -> resumeScanning())
                .show());
    }

    private static int concernText(LinkSafety.Concern concern) {
        switch (concern) {
            case PLAIN_HTTP: return R.string.concern_plain_http;
            case TEXT_BEFORE_AT: return R.string.concern_text_before_at;
            case INTERNATIONAL_HOST: return R.string.concern_international_host;
            case NUMERIC_HOST: return R.string.concern_numeric_host;
            default: throw new IllegalArgumentException("Unhandled concern: " + concern);
        }
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, getString(R.string.no_torch), Toast.LENGTH_SHORT).show();
            return;
        }
        boolean enabled = camera.getCameraInfo().getTorchState().getValue() != null
                && camera.getCameraInfo().getTorchState().getValue() == androidx.camera.core.TorchState.ON;
        camera.getCameraControl().enableTorch(!enabled);
    }

    private void stopCamera() {
        if (analysis != null) analysis.clearAnalyzer();
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try { ProcessCameraProvider.getInstance(this).get().unbindAll(); } catch (Exception ignored) { }
        }, ContextCompat.getMainExecutor(this));
        preview = null;
        analysis = null;
        camera = null;
        decoder = null;
    }

    @Override
    protected void onDestroy() {
        if (activeDialog != null && activeDialog.isShowing()) activeDialog.dismiss();
        activeDialog = null;
        stopCamera();
        analysisExecutor.shutdown();
        super.onDestroy();
    }

    /** Remembers the visible dialog so it can be resized on rotation and dismissed on exit. */
    private void track(AlertDialog dialog) {
        activeDialog = dialog;
    }

    @SuppressWarnings("deprecation")
    private int displayRotation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.Display display = getDisplay();
            if (display != null) return display.getRotation();
        }
        return getWindowManager().getDefaultDisplay().getRotation();
    }

    private Button button(String label, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setIncludeFontPadding(false);
        button.setMinHeight(dp(48));
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(16);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(backgroundColor);
        shape.setCornerRadius(dp(18));
        button.setBackground(shape);
        return button;
    }

    private Button outlinedButton(String label) {
        Button button = button(label, Color.TRANSPARENT, color(R.color.teal));
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(18));
        outline.setStroke(dp(1), color(R.color.teal));
        button.setBackground(outline);
        return button;
    }

    private TextView text(String value, int size, int textColor) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(textColor);
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        return view;
    }

    private int color(int resource) { return ContextCompat.getColor(this, resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
