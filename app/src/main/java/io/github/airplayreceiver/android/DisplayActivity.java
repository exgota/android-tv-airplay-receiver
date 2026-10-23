package io.github.airplayreceiver.android;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import io.github.airplayreceiver.R;

/** Full screen output for mirrored video and photos. Back ends the session. */
public final class DisplayActivity extends Activity implements SurfaceHolder.Callback, VideoRenderer.SizeListener {

    private static DisplayActivity current;

    private FrameLayout container;
    private SurfaceView surfaceView;
    private ImageView photoView;
    private int videoWidth;
    private int videoHeight;

    static void closeIfOpen() {
        if (current != null) {
            current.finish();
        }
    }

    static void refreshPhoto() {
        if (current != null) {
            current.showPhoto();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.display_activity);
        container = (FrameLayout) findViewById(R.id.display_container);
        surfaceView = (SurfaceView) findViewById(R.id.video_surface);
        photoView = (ImageView) findViewById(R.id.photo_view);
        surfaceView.getHolder().addCallback(this);
        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    fitVideo();
                }
            }
        });
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        ReceiverService service = ReceiverService.instance();
        if (service == null || (!service.isMirroring() && service.currentPhoto() == null)) {
            finish();
            return;
        }
        service.videoRenderer().setSizeListener(this);
        showPhoto();
    }

    @Override
    protected void onPause() {
        ReceiverService service = ReceiverService.instance();
        if (service != null) {
            service.videoRenderer().setSizeListener(null);
        }
        if (current == this) {
            current = null;
        }
        super.onPause();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            ReceiverService service = ReceiverService.instance();
            if (service != null) {
                service.disconnectClient();
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void showPhoto() {
        ReceiverService service = ReceiverService.instance();
        Bitmap photo = service == null ? null : service.currentPhoto();
        if (photo != null) {
            photoView.setImageBitmap(photo);
            photoView.setVisibility(View.VISIBLE);
        } else {
            photoView.setImageDrawable(null);
            photoView.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ video surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        ReceiverService service = ReceiverService.instance();
        if (service != null) {
            service.videoRenderer().setSurface(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        ReceiverService service = ReceiverService.instance();
        if (service != null) {
            service.videoRenderer().setSurface(null);
        }
    }

    @Override
    public void onVideoSize(final int width, final int height) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                videoWidth = width;
                videoHeight = height;
                fitVideo();
            }
        });
    }

    /** Sizes the surface to the largest rectangle with the video's aspect ratio (letterboxing). */
    private void fitVideo() {
        int availableWidth = container.getWidth();
        int availableHeight = container.getHeight();
        if (availableWidth == 0 || availableHeight == 0 || videoWidth <= 0 || videoHeight <= 0) {
            return;
        }
        int width = availableWidth;
        int height = (int) ((long) availableWidth * videoHeight / videoWidth);
        if (height > availableHeight) {
            height = availableHeight;
            width = (int) ((long) availableHeight * videoWidth / videoHeight);
        }
        FrameLayout.LayoutParams parameters = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (parameters.width != width || parameters.height != height) {
            parameters.width = width;
            parameters.height = height;
            surfaceView.setLayoutParams(parameters);
        }
    }
}
