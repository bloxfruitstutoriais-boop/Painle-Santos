package painel.sensi.santos;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import android.hardware.input.IInputManager;
import android.view.InputEvent;
import android.view.InputDevice;
import android.view.MotionEvent.PointerProperties;
import android.view.MotionEvent.PointerCoords;
import android.view.InputDevice;
import android.hardware.input.InputManager;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * Sessão visual do Santos Team. Diferente do fluxo antigo, o display tem uma
 * Surface própria anexada à tela do telefone; por isso o HOME não fica preso
 * em um display virtual invisível.
 */
public final class DesktopSessionActivity extends Activity implements SurfaceHolder.Callback {
    private static final int DESKTOP_WIDTH = 1920;
    private static final int DESKTOP_HEIGHT = 1080;
    private static final int DESKTOP_DENSITY = 240;
    private static WeakReference<DesktopSessionActivity> active = new WeakReference<>(null);

    private FrameLayout root;
    private SurfaceView surfaceView;
    private TextView status;
    private VirtualDisplay virtualDisplay;
    private int displayId = -1;
    private float downX;
    private float downY;
    private long downTime;
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
        root = new FrameLayout(this);
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
        status.setText("Santos Team · preparando desktop…");
        FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        statusParams.topMargin = 18;
        root.addView(status, statusParams);
        setContentView(root);
    }

    @Override public void surfaceCreated(SurfaceHolder holder) {
        startVirtualDesktop(holder.getSurface());
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        releaseDisplay();
    }

    private void startVirtualDesktop(Surface surface) {
        if (virtualDisplay != null || surface == null) return;
        try {
            DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            // PRESENTATION é o mesmo caminho usado pelo exemplo público
            // SimpleVirtualDisplay; o conteúdo é renderizado na SurfaceView.
            virtualDisplay = manager.createVirtualDisplay(
                    "Santos Team Desktop",
                    DESKTOP_WIDTH,
                    DESKTOP_HEIGHT,
                    DESKTOP_DENSITY,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            );
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                fail("Android não criou o display virtual");
                return;
            }
            displayId = virtualDisplay.getDisplay().getDisplayId();
            status.setText("Desktop Santos Team · display " + displayId + " · abrindo launcher…");
            ShellManager.launchHomeOnDisplay(this, displayId, (ok, message) -> runOnUiThread(() -> {
                if (closing) return;
                status.setText(ok
                        ? "Desktop ativo · toque direto / trackpad · display " + displayId
                        : "Desktop criado, mas o launcher falhou: " + message);
                status.setVisibility(ok ? View.GONE : View.VISIBLE);
            }));
        } catch (Throwable error) {
            fail("Falha ao criar sessão: " + error.getClass().getSimpleName());
        }
    }

    private boolean forwardTouch(MotionEvent original) {
        if (displayId < 0) return true;
        try {
            MotionEvent event = MotionEvent.obtain(original);
            float sx = DESKTOP_WIDTH / (float) Math.max(1, surfaceView.getWidth());
            float sy = DESKTOP_HEIGHT / (float) Math.max(1, surfaceView.getHeight());
            event.setLocation(event.getX() * sx, event.getY() * sy);
            try {
                MotionEvent.class.getMethod("setDisplayId", int.class).invoke(event, displayId);
            } catch (Throwable ignored) { }
            if (original.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = original.getX(); downY = original.getY(); downTime = System.currentTimeMillis();
            }
            InputBridge.inject(event);
            event.recycle();
            return true;
        } catch (Throwable error) {
            status.setText("Entrada indisponível: autorize o Santos Team no Shizuku");
            status.setVisibility(View.VISIBLE);
            return true;
        }
    }

    private void fail(String message) {
        if (status != null) {
            status.setText(message);
            status.setVisibility(View.VISIBLE);
        }
    }

    private void releaseDisplay() {
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Throwable ignored) { }
            virtualDisplay = null;
        }
        displayId = -1;
    }

    @Override protected void onDestroy() {
        closing = true;
        releaseDisplay();
        if (active.get() == this) active.clear();
        super.onDestroy();
    }

    /** Ponte de input usada somente para eventos produzidos pelo usuário nesta tela. */
    private static final class InputBridge {
        private static IInputManager manager;
        private static IInputManager manager() throws Exception {
            if (manager == null) {
                manager = IInputManager.Stub.asInterface(
                        new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.INPUT_SERVICE)));
            }
            return manager;
        }
        static void inject(InputEvent event) throws Exception {
            manager().injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    }
}
