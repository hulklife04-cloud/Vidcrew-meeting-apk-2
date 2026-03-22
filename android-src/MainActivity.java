package com.vidcrew.meet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public class MainActivity extends BridgeActivity {

    private static final int SCREEN_CAPTURE_REQUEST = 1001;
    private MediaProjectionManager projManager;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Handler frameHandler;
    private boolean capturing = false;
    private int screenW, screenH, screenDpi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        frameHandler = new Handler(Looper.getMainLooper());
        bridge.getWebView().post(() -> {
            bridge.getWebView().addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        });
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void startCapture() {
            if (capturing) return;
            runOnUiThread(() -> {
                Intent intent = projManager.createScreenCaptureIntent();
                startActivityForResult(intent, SCREEN_CAPTURE_REQUEST);
            });
        }

        @JavascriptInterface
        public void stopCapture() {
            stopScreenCapture();
            runOnUiThread(() -> callJS("if(window._onScreenStopped)window._onScreenStopped();"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                startScreenCapture(resultCode, data);
            } else {
                callJS("if(window._onScreenDenied)window._onScreenDenied();");
            }
        }
    }

    private void startScreenCapture(int resultCode, Intent data) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        screenDpi = metrics.densityDpi;
        float scale = Math.min(1.0f, 720.0f / Math.max(metrics.widthPixels, metrics.heightPixels));
        screenW = ((int)(metrics.widthPixels * scale)) & ~1;
        screenH = ((int)(metrics.heightPixels * scale)) & ~1;
        mediaProjection = projManager.getMediaProjection(resultCode, data);
        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "VidcrewCapture", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, null
        );
        capturing = true;
        final int w = screenW, h = screenH;
        callJS("if(window._onScreenStarted)window._onScreenStarted("+w+","+h+");");
        Thread frameThread = new Thread(() -> {
            while (capturing) {
                try {
                    Image img = imageReader.acquireLatestImage();
                    if (img != null) {
                        String b64 = imageToBase64(img);
                        img.close();
                        if (b64 != null) {
                            final String frame = b64;
                            frameHandler.post(() ->
                                callJS("if(window._onScreenFrame)window._onScreenFrame('"+frame+"');")
                            );
                        }
                    }
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {}
            }
        });
        frameThread.setDaemon(true);
        frameThread.start();
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopScreenCapture();
                callJS("if(window._onScreenStopped)window._onScreenStopped();");
            }
        }, null);
    }

    private void stopScreenCapture() {
        capturing = false;
        if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
        if (imageReader != null) { imageReader.close(); imageReader = null; }
        if (mediaProjection != null) { mediaProjection.stop(); mediaProjection = null; }
    }

    private String imageToBase64(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenW;
            Bitmap bitmap = Bitmap.createBitmap(
                screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);
            if (rowPadding != 0) bitmap = Bitmap.createBitmap(bitmap, 0, 0, screenW, screenH);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out);
            bitmap.recycle();
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return null; }
    }

    private void callJS(String js) {
        WebView wv = bridge.getWebView();
        if (wv != null) wv.post(() -> wv.evaluateJavascript(js, null));
    }

    @Override
    public void onDestroy() {
        stopScreenCapture();
        super.onDestroy();
    }
}
