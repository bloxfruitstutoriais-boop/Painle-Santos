package painel.sensi.santos;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * Hospeda o espelho do display overlay real. Não cria um segundo
 * VirtualDisplay: o display é solicitado pelo Shizuku e o WindowManager é
 * usado para anexá-lo à SurfaceView, como no Dextop.
 */
public final class DesktopSessionActivity extends Activity implements SurfaceHolder.Callback {
    private static WeakReference<DesktopSessionActivity> active = new WeakReference<>(null);
    private SurfaceView surfaceView;
    private TextView status;
    private int displayId = -1;
    private boolean closing;

    public static void stopActive() {
        DesktopSessionActivity activity = active.get();
        if (activity != null) activity.runOnUiThread(activity::finish);
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        active = new WeakReference<>(this);
        Window window = getWindow();
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setNavigationBarColor(Color.BLACK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        surfaceView.setOnTouchListener((v, event) -> forwardTouch(event));
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setPadding(18, 10, 18, 10);
        status.setBackgroundColor(0xB0000000);
        status.setText("Santos Team · criando display desktop…");
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusParams.topMargin = 18;
        root.addView(status, statusParams);
        setContentView(root);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        status.setText("Santos Team · solicitando display overlay ao Android…");
        ShellManager.startDesktopMode(this, (ok, message) -> runOnUiThread(() -> {
            if (closing) return;
            if (!ok) {
                showError("Desktop não foi criado: " + message);
                return;
            }
            displayId = readNewestOverlayDisplay();
            if (displayId < 0) {
                showError("O Android não publicou o display overlay criado");
                return;
            }
            status.setText("Desktop publicado · espelhando display " + displayId + "…");
            ShellManager.attachDesktopMirror(displayId, surfaceView, (mirrorOk, mirrorMessage) ->
                    runOnUiThread(() -> {
                        if (closing) return;
                        if (mirrorOk) {
                            status.setVisibility(View.GONE);
                        } else {
                            showError(mirrorMessage);
                            ShellManager.stopDesktopMode(this, (ignored, ignoredMessage) -> { });
                        }
                    }));
        }));
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        ShellManager.releaseDesktopMirror();
    }

    private int readNewestOverlayDisplay() {
        android.hardware.display.DisplayManager manager =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (manager == null) return -1;
        int result = -1;
        for (android.view.Display display : manager.getDisplays()) {
            if (display.getDisplayId() == android.view.Display.DEFAULT_DISPLAY) continue;
            boolean overlay = false;
            try {
                overlay = ((Integer) android.view.Display.class.getMethod("getType")
                        .invoke(display)) == 4;
            } catch (Throwable ignored) { }
            if (overlay) result = display.getDisplayId();
        }
        return result;
    }

    private boolean forwardTouch(MotionEvent original) {
        if (displayId < 0) return true;
        try {
            MotionEvent event = MotionEvent.obtain(original);
            float sx = 1920f / Math.max(1, surfaceView.getWidth());
            float sy = 1080f / Math.max(1, surfaceView.getHeight());
            event.setLocation(event.getX() * sx, event.getY() * sy);
            MotionEvent.class.getMethod("setDisplayId", int.class).invoke(event, displayId);
            InputBridge.inject(event);
            event.recycle();
        } catch (Throwable error) {
            showError("Entrada recusada pelo Android/Shizuku: " + error.getClass().getSimpleName());
        }
        return true;
    }

    private void showError(String message) {
        if (status != null) {
            status.setText(message);
            status.setVisibility(View.VISIBLE);
        }
    }

    @Override protected void onDestroy() {
        closing = true;
        ShellManager.releaseDesktopMirror();
        ShellManager.stopDesktopMode(this, (ignored, ignoredMessage) -> { });
        if (active.get() == this) active.clear();
        super.onDestroy();
    }

    private static final class InputBridge {
        private static android.os.IBinder manager;
        private static android.os.IBinder manager() {
            if (manager == null) {
                manager = new rikka.shizuku.ShizukuBinderWrapper(
                        rikka.shizuku.SystemServiceHelper.getSystemService("input"));
            }
            return manager;
        }
        static void inject(android.view.InputEvent event) throws Exception {
            android.os.Parcel data = android.os.Parcel.obtain();
            android.os.Parcel reply = android.os.Parcel.obtain();
            try {
                data.writeInterfaceToken("android.hardware.input.IInputManager");
                data.writeInt(1);
                event.writeToParcel(data, 0);
                data.writeInt(1);
                manager().transact(1, data, reply, 0);
                reply.readException();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    }
}
