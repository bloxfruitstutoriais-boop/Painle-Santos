package painel.sensi.santos;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.pm.ConfigurationInfo;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

public class OverlayService extends Service {
    public static final String ACTION_OPEN = "painel.sensi.santos.OPEN";
    public static final String ACTION_SHOW = "painel.sensi.santos.SHOW";
    public static final String ACTION_HIDE = "painel.sensi.santos.HIDE";
    public static final String ACTION_STOP = "painel.sensi.santos.STOP";
    public static final String EXTRA_REQUEST_ID = "request_id"; // CORRIGIDO BUG1: mantém o identificador de abertura.
    public static final String EXTRA_LICENSE_KEY = MainActivity.EXTRA_LICENSE_KEY; // CORRIGIDO BUG2: recebe a key ativa para Revalidar key.
    private static final String CHANNEL = "santos_floating";
    private static final int NOTIFICATION_ID = 8183;
    private static final int BUBBLE_SIZE_DP = 54;
    private static final int PANEL_WIDTH_DP = 360;
    private static final int PANEL_MAX_HEIGHT_DP = 420;

    private WindowManager wm;
    private FrameLayout panel;
    private LinearLayout panelBody;
    private FrameLayout pages;
    private ImageView bubble;
    private TextView status;
    // CORRIGIDO: o log é acumulado; mensagens novas nunca sobrescrevem as anteriores.
    private final StringBuilder statusLog = new StringBuilder();
    private TextView shizukuStatus;
    private TextView licenseStatus; // CORRIGIDO BUG2: mostra na overlay os dias restantes calculados.
    private TextView revalidateKeyButton; // CORRIGIDO BUG2: botão para limpar cache e validar novamente.
    private TextView fpsStatus;
    private TextView refreshStatus;
    private TextView refreshApply;
    private TextView refreshRestore;
    private TextView resolutionSave;
    private TextView resolutionRestore;
    private Spinner resolutionSelector;
    private Spinner dpiSelector;
    private Spinner flagshipSelector;
    private final List<FlagshipPreset> flagshipPresets = new ArrayList<>();
    private SeekBar stretchXSlider;
    private SeekBar stretchYSlider;
    private Spinner rendererSelector;
    private TextView rendererStatus;
    private TextView rendererApply;
    private TextView displayProfileStatus;
    private TextView displayApply;
    private TextView displayReset;
    private TextView diagnosticDetails;
    private TextView diagnosticToggle;
    private TextView deviceInfo;
    private TextView axModeStatus;
    private ColorWheelView colorWheel;
    private TextView colorStatus;
    private int panelAccentColor = Ui.BRIGHT;
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (displayId == Display.DEFAULT_DISPLAY && !destroyed) {
                mainHandler.post(() -> {
                    if (!destroyed) {
                        refreshDisplayModes();
                        updateRefreshStatus();
                    }
                });
            }
        }
    }; 
    // Linha expansível do LOG; não é um botão de ação.
    private TextView logSummary;
    private boolean logExpanded;
    // CORRIGIDO ALONGAR TELA: o comando legado wm overscan não participa
    // da UI nem do caminho de aplicação.
    private int physicalDisplayWidth;
    private int physicalDisplayHeight;
    private int physicalDisplayDensity;
    // ADICIONADO: rotação efetiva lida pelo Shizuku para montar target landscape/portrait.
    private int currentDisplayRotation = -1;
    // ADICIONADO: capability observada pelo comando remoto, não presumida pela UI.
    private boolean overscanSupported;
    private final List<DisplayProfile> resolutionProfiles = new ArrayList<>();
    private final List<Integer> densityProfiles = new ArrayList<>();
    private static final class DisplayProfile {
        final String label;
        final int width;
        final int height;
        DisplayProfile(String label, int width, int height) {
            this.label = label;
            this.width = width;
            this.height = height;
        }
    }
    private static final class FlagshipPreset {
        final String name;
        final int officialHeight;
        final int officialWidth;
        FlagshipPreset(String name, int officialHeight, int officialWidth) {
            this.name = name;
            this.officialHeight = officialHeight;
            this.officialWidth = officialWidth;
        }
        String label() {
            return name + " — " + officialHeight + "×" + officialWidth;
        }
    }
    private View[] tabViews;
    private TextView activeTabTitle;
    private android.widget.EditText gamePackageInput;
    private WindowManager.LayoutParams panelParams;
    private SharedPreferences prefs;
    private boolean collapsed = true;
    private boolean hiddenForCapture;
    private int selectedTab;
    private int selectedRefreshHz = -1;
    private boolean updatingControls;
    private boolean nativeDisplayInfoRequested;
    private boolean nativeDisplayInfoLoaded;
    private long nextNativeDisplayInfoAttemptAt;
    private boolean closeRequested;
    private String activeLicenseKey = ""; // CORRIGIDO BUG2: mantém a key da sessão sem forçar persistência quando o usuário optou por não salvar.
    private LicenseKeyInfo licenseInfo; // CORRIGIDO BUG2: snapshot único usado para habilitar/bloquear recursos.
    private LicenseValidator.Request licenseRequest; // CORRIGIDO BUG2: cancela revalidação quando o serviço morre.
    private long requestId = -1L;
    private int lastStartId;
    // ADICIONADO: impede callback tardio de tocar Views depois de onDestroy.
    private boolean destroyed;
    // ADICIONADO: fallback explícito para toda atualização visual na Main Looper.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable liveStretchApply;
    // CORRIGIDO: geração/coalescência impede que um slider aplique valores
    // intermediários ou um callback antigo depois do último preview local.
    private long stretchPreviewGeneration;
    private long displayOperationGeneration;
    // Se a criação do foreground/WindowManager falhar, a instância não pode ser
    // reutilizada pela Activity nem continuar recebendo ACTION_OPEN.
    private boolean initializationFailed;
    // A resolução/DPI do display pode mudar enquanto a janela do painel vive.
    // Guardar a densidade da criação evita que resizePanel() redimensione a UI
    // para outro tamanho depois da aplicação e corrige a imagem/layout do painel.
    private float panelBaseDensity = 1f;
    private final Map<String, Switch> toggleViews = new HashMap<>();
    private final Map<String, TextView> toggleDescriptions = new HashMap<>();
    private final Map<String, String> toggleOriginalDescriptions = new HashMap<>();
    private final Map<String, SeekBar> sliderViews = new HashMap<>();
    private final Map<String, TextView> sliderValues = new HashMap<>();
    private final Map<String, Integer> sliderMins = new HashMap<>();
    private final Map<String, Integer> sliderMaxes = new HashMap<>();
    private final Map<String, Integer> sliderDefaults = new HashMap<>();
    private final Map<Integer, TextView> refreshChoices = new HashMap<>();
    private final Map<Integer, Boolean> refreshSupport = new HashMap<>();
    private final Set<TextView> licenseRestrictedButtons = new HashSet<>(); // CORRIGIDO BUG2: lista ações bloqueadas para keys com menos de 7 dias.
    private final Map<TextView, String> licenseRestrictedLabels = new HashMap<>(); // CORRIGIDO BUG2: restaura o texto original quando a key libera tudo.
    private final Set<String> unavailableShellFeatures = new HashSet<>();
    private boolean shellCapabilitiesKnown;
    private boolean shellCapabilityProbeInProgress;
    // Um único toggle dependente do Shizuku por vez evita que callbacks
    // concorrentes calculem o estado a partir de preferências ainda antigas.
    private final Set<String> pendingToggleOperations = new HashSet<>();
    // CORRIGIDO ALONGAR TELA: o botão original de aplicar usa o helper CAT;
    // nenhum botão de preset novo é criado.
    private final Map<String, Integer> pendingSliderPrevious = new HashMap<>();
    // O binder pode morrer depois de o painel já estar aberto. Nesse caso a
    // ponte é invalidada imediatamente; nenhuma função fica habilitada por
    // causa de uma autorização antiga em SharedPreferences.
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> // CORRIGIDO BUG1: listener explícito acompanha a chegada do binder durante a vida do serviço.
            mainHandler.post(() -> {
                if (destroyed) return;
                Log.d(FLOW_TAG, "OVERLAY_SHIZUKU_BINDER_RECEIVED");
                refreshShizuku();
            });
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> mainHandler.post(() -> {
        Log.e(FLOW_TAG, "OVERLAY_SHIZUKU_BINDER_DEAD"); // LOG ADICIONADO
        if (destroyed) return;
        bridgeVerified = false;
        bridgeTestInProgress = false;
        nativeDisplayInfoRequested = false;
        nativeDisplayInfoLoaded = false;
        shellCapabilitiesKnown = false;
        shellCapabilityProbeInProgress = false;
        unavailableShellFeatures.clear();
        ShizukuBridge.onBinderDead();
        for (String key : new HashSet<>(pendingToggleOperations)) {
            if (prefs != null) setToggleChecked(key, prefs.getBoolean("feature_" + key, false));
        }
        pendingToggleOperations.clear();
        updateCapabilityStates();
        message(false, "Shizuku foi desativado ou o binder morreu; funções de sistema bloqueadas");
    });

    private Choreographer choreographer;
    private long fpsWindowStart;
    private int fpsFrames;
    private boolean measuringFps;
    private boolean bridgeVerified;
    private boolean bridgeTestInProgress;
    private boolean shizukuStatusProbeInProgress;
    // CORRIGIDO: callbacks Shizuku só são registrados após ação explícita do usuário;
    // nenhuma inicialização do binder entra no caminho de criação da overlay.
    private boolean binderReceivedListenerRegistered;
    private boolean binderDeadListenerRegistered;
    private boolean permissionResultListenerRegistered;
    private final Choreographer.FrameCallback fpsCallback = frameTimeNanos -> {
        if (!measuringFps) return;
        if (fpsWindowStart == 0L) fpsWindowStart = frameTimeNanos;
        fpsFrames++;
        long elapsed = frameTimeNanos - fpsWindowStart;
        if (elapsed >= 1_000_000_000L) {
            int measured = Math.round((float) fpsFrames * 1_000_000_000f / elapsed);
            updateFpsText(measured);
            fpsFrames = 0;
            fpsWindowStart = frameTimeNanos;
        }
        if (measuringFps && choreographer != null) scheduleFpsCallback();
    };

    private void scheduleFpsCallback() {
        if (measuringFps && choreographer != null) choreographer.postFrameCallback(fpsCallback);
    }

    // CORRIGIDO: referência fraca evita que um campo static retenha o Service
    // e toda a hierarquia de Views caso o Android atrase o onDestroy().
    private static volatile WeakReference<OverlayService> activeInstance = new WeakReference<>(null);
    private static final String FLOW_TAG = "DEBUG_FLOW";
    // LOG ADICIONADO: resposta de autorização é observada pelo próprio serviço.
    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            (requestCode, grantResult) -> mainHandler.post(() -> {
                if (destroyed || requestCode != ShizukuBridge.REQUEST_CODE) return;
                Log.d(FLOW_TAG, "OVERLAY_SHIZUKU_PERMISSION_RESULT granted="
                        + (grantResult == PackageManager.PERMISSION_GRANTED)); // LOG ADICIONADO
                bridgeVerified = false;
                refreshShizuku();
                message(grantResult == PackageManager.PERMISSION_GRANTED,
                        grantResult == PackageManager.PERMISSION_GRANTED
                                ? "Shizuku autorizado; teste a ponte antes de usar funções."
                                : "Autorização do Shizuku negada.");
            });
    // ADICIONADO: estado explícito da janela; isAttachedToWindow pode mudar durante remove/add.
    private boolean windowAdded;

    public static boolean isAlive() {
        // ADICIONADO: leitura segura usada pela Activity durante a reabertura.
        OverlayService service = activeInstance.get();
        return service != null && !service.destroyed && !service.closeRequested
                && !service.initializationFailed;
    }

    @Override public void onCreate() {
        super.onCreate();
        Log.d(FLOW_TAG, "OVERLAY_ON_CREATE"); // LOG ADICIONADO
        // CORRIGIDO: registrar a instância antes de processar qualquer ACTION_OPEN.
        activeInstance = new WeakReference<>(this); // CORRIGIDO: não manter o Service vivo por referência estática forte.
        windowAdded = false;
        initializationFailed = false;
        // CORRIGIDO: o Shizuku não é inicializado nem observado durante o startup.
        // Os listeners só são registrados por registerShizukuListenersIfNeeded(),
        // chamado após o usuário tocar em Autorizar/Testar ponte.
        // CORRIGIDO: preferências devem existir antes de qualquer reset de estado.
        prefs = getSharedPreferences("santos_session", MODE_PRIVATE);
        // CORRIGIDO: estado de vida inicia antes de qualquer callback/WindowManager.
        destroyed = false;
        bridgeVerified = false;
        bridgeTestInProgress = false;
        hiddenForCapture = false;
        panelAccentColor = prefs.getInt("panel_accent_color", Ui.BRIGHT);
        collapsed = prefs.getBoolean("overlay_collapsed", true);
        closeRequested = false;
        prefs.edit().putBoolean("overlay_ready", false)
                .remove("overlay_ready_request_id")
                .remove("overlay_error").apply();
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (displayManager != null) displayManager.registerDisplayListener(displayListener, mainHandler);
            panelBaseDensity = Math.max(.5f, getResources().getDisplayMetrics().density);
            createChannel();
            startForegroundCompat();
            buildPanel();
            Log.d(FLOW_TAG, "OVERLAY_PANEL_BUILT"); // LOG ADICIONADO
            // O reinício do serviço não reexecuta otimizações salvas automaticamente.
            // CORRIGIDO: nenhuma sondagem Shizuku automática durante a criação.
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "OVERLAY_INIT_FAILED", error); // TRY/CATCH ADICIONADO
            // RemoteServiceException, SecurityException e erro de
            // notification channel ficam no Logcat e na preferência de diagnóstico.
            // Não é sucesso silencioso: a instância é marcada como inválida e
            // a Activity receberá o diagnóstico persistido para uma nova tentativa.
            initializationFailed = true;
            Log.e("OverlayService", "Falha ao montar o flutuante", error);
            prefs.edit().putBoolean("overlay_ready", false)
                    .putString("overlay_error", error.getClass().getSimpleName() + ": "
                            + (error.getMessage() == null ? "sem mensagem" : error.getMessage())).apply();
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStartId = startId;
        Log.d(FLOW_TAG, "OVERLAY_ON_START_COMMAND action="
                + (intent == null ? "null" : intent.getAction())); // LOG ADICIONADO
        if (initializationFailed || destroyed) return START_NOT_STICKY;
        String action = intent == null ? null : intent.getAction();
        if (intent != null && intent.hasExtra(EXTRA_REQUEST_ID)) {
            long incomingRequestId = intent.getLongExtra(EXTRA_REQUEST_ID, -1L);
            if (incomingRequestId > 0L) requestId = incomingRequestId;
        }
        if (intent != null && intent.hasExtra(EXTRA_LICENSE_KEY)) { // CORRIGIDO BUG2: atualiza a key ativa sem tocar a UI fora da Main Looper.
            activeLicenseKey = intent.getStringExtra(EXTRA_LICENSE_KEY); // CORRIGIDO BUG2: recebe somente a sessão corrente.
            if (activeLicenseKey == null) activeLicenseKey = ""; // CORRIGIDO BUG2: evita NullPointerException no botão de revalidação.
            activeLicenseKey = activeLicenseKey.trim(); // CORRIGIDO BUG2: normaliza antes de consultar o fingerprint.
            refreshLicenseInfo(); // CORRIGIDO BUG2: recalcula dias após receber a key.
            updateCapabilityStates(false); // CORRIGIDO: adia sondagem Shizuku até a janela estar anexada.
        }
        if (intent != null && (ACTION_OPEN.equals(action) || ACTION_SHOW.equals(action))) {
            hiddenForCapture = false;
            setFeatureState("hide_for_capture", false);
            setCaptureProtection(false);
        }
        if (ACTION_STOP.equals(action)) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_HIDE.equals(action)) {
            hideForCapture();
        } else if (ACTION_OPEN.equals(action) || ACTION_SHOW.equals(action)) {
            // Reabrir deve funcionar tanto na mesma instância quanto depois de
            // um fechamento que ainda esteja terminando a animação.
            cancelPendingClose();
            // ACTION_OPEN/ACTION_SHOW reexibem a mesma janela explicitamente.
            prefs.edit().putBoolean("overlay_hidden", false).apply();
            resizePanel();

            updateForegroundNotification();
            showPanel();
        } else if (!hiddenForCapture) {
            showPanel();
        }
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        try {
            if (!destroyed && panel != null && panelBody != null && bubble != null
                    && panelParams != null && windowAdded && panel.isAttachedToWindow()) {
                panel.post(() -> {
                    if (!destroyed && panel != null && panel.isAttachedToWindow()) resizePanel();
                });
            }
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "OVERLAY_CONFIGURATION_CHANGED_FAILED", error); // TRY/CATCH ADICIONADO
        }
    }

    @Override public void onDestroy() {
        Log.d(FLOW_TAG, "OVERLAY_ON_DESTROY"); // LOG ADICIONADO
        // CORRIGIDO: callbacks e animações pendentes passam a ignorar este serviço.
        destroyed = true;
        measuringFps = false;
        closeRequested = true;
        if (choreographer != null) choreographer.removeFrameCallback(fpsCallback);
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(displayListener); }
            catch (Throwable error) { Log.w(FLOW_TAG, "DISPLAY_LISTENER_REMOVE_FAILED", error); }
        }
        if (liveStretchApply != null) mainHandler.removeCallbacks(liveStretchApply);
        stretchPreviewGeneration++;
        displayOperationGeneration++;
        if (panel != null) panel.animate().cancel();
        if (licenseRequest != null) { licenseRequest.cancel(); licenseRequest = null; } // CORRIGIDO BUG2: callback de revalidação não toca Views após onDestroy.
        if (prefs != null && requestId > 0L
                && prefs.getLong("overlay_ready_request_id", -1L) == requestId) {
            prefs.edit().putBoolean("overlay_ready", false)
                    .remove("overlay_ready_request_id").apply();
        }
        remove(panel);
        mainHandler.removeCallbacksAndMessages(null);
        // CORRIGIDO: impedir que a Activity considere vivo um serviço destruído.
        if (activeInstance.get() == this) {
            activeInstance = new WeakReference<>(null); // CORRIGIDO: liberar a referência ao destruir o serviço.
        }
        // CORRIGIDO: só tocar a API Shizuku no encerramento se ela foi usada
        // explicitamente; startup sem Shizuku permanece totalmente independente.
        if (binderReceivedListenerRegistered || binderDeadListenerRegistered
                || permissionResultListenerRegistered) {
            try {
                if (binderReceivedListenerRegistered) {
                    Shizuku.removeBinderReceivedListener(binderReceivedListener);
                    binderReceivedListenerRegistered = false;
                }
                if (binderDeadListenerRegistered) {
                    Shizuku.removeBinderDeadListener(binderDeadListener);
                    binderDeadListenerRegistered = false;
                }
                if (permissionResultListenerRegistered) {
                    Shizuku.removeRequestPermissionResultListener(permissionResultListener);
                    permissionResultListenerRegistered = false;
                }
            } catch (Throwable error) {
                Log.e(FLOW_TAG, "OVERLAY_SHIZUKU_LISTENER_REMOVE_FAILED", error);
            }
        }
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "PAINEL SANTOS",
                    NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Notificação do painel flutuante");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification buildForegroundNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_santos)
                .setContentTitle("PAINEL SANTOS")
                .setContentText(hiddenForCapture
                        ? "Painel oculto durante transmissão · jogo continua visível"
                        : "Painel aberto")
                // CORRIGIDO: categoria explícita reduz rejeições de notificação FGS.
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setShowWhen(false);
        // Não adicionar ação de notificação para reabrir: ao ocultar o painel
        // o foreground service é encerrado e a notificação é removida.
        return builder.build();
    }

    private void updateForegroundNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildForegroundNotification());
    }

    private void startForegroundCompat() {
        // CORRIGIDO: o channel e o ícone são criados antes deste ponto.
        Notification n = buildForegroundNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            // CORRIGIDO: specialUse coincide com o tipo declarado no manifesto.
            startForeground(NOTIFICATION_ID, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void startFpsMonitor() {
        choreographer = Choreographer.getInstance();
        measuringFps = true;
        fpsWindowStart = 0L;
        fpsFrames = 0;
        choreographer.postFrameCallback(fpsCallback);
    }

    private void updateFpsText(int measuredPanelFps) {
        if (destroyed || fpsStatus == null) return;
        String display = displayRefreshText();
        fpsStatus.setText("FPS Live (Choreographer): " + measuredPanelFps
                + " · fonte=Choreographer · confiança=frames do próprio painel"
                + " · Taxa do display: " + display
                + " · FPS do jogo não exposto pelo Android");
    }

    private String displayRefreshText() {
        try {
            Display display = primaryDisplay();
            float rate = display == null ? 0f : display.getRefreshRate();
            return rate > 0f ? String.format(Locale.US, "%.0f Hz", rate) : "indisponível";
        } catch (Throwable ignored) { return "indisponível"; }
    }

    private void remove(View view) {
        // CORRIGIDO: usar estado próprio evita tentar remover uma View já removida.
        boolean shouldRemove = windowAdded || (view != null && view.isAttachedToWindow());
        windowAdded = false;
        if (shouldRemove && view != null && wm != null) {
            try {
                // A remoção síncrona evita que uma reabertura imediata veja a
                // View como ainda anexada e tente addView em duplicidade.
                wm.removeViewImmediate(view);
            } catch (Throwable error) {
                // CORRIGIDO: BadToken/IllegalArgumentException não somem no catch.
                Log.w("OverlayService", "Falha removendo janela do overlay", error);
            }
        }
    }

    private void cancelPendingClose() {
        closeRequested = false;
        if (panel != null) {
            panel.animate().cancel();
            panel.setAlpha(1f);
        }
    }

    private int windowType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private LinearLayout.LayoutParams lp(int width, int height, int weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    private void buildPanel() {
        panel = new FrameLayout(this);
        if (hiddenForCapture) {
            // CORRIGIDO: a janela começa invisível sem bloquear a transmissão.
            panel.setVisibility(View.INVISIBLE);
        }
        // cards e cabeçalho compacto. Nenhum controle de hack/injeção é criado.
        panel.setBackground(Ui.rounded(0xFF061423, 18, this));
        panel.setElevation(Ui.dp(this, 12));
        panelBody = new LinearLayout(this);
        panelBody.setOrientation(LinearLayout.HORIZONTAL);
        panelBody.setPadding(0, 0, 0, 0);
        panel.addView(panelBody, new FrameLayout.LayoutParams(-1, -1));

            ScrollView tabRailScroll = new ScrollView(this);
            tabRailScroll.setFillViewport(true);
            tabRailScroll.setVerticalScrollBarEnabled(true);
            tabRailScroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            LinearLayout sideRail = new LinearLayout(this);
            sideRail.setOrientation(LinearLayout.VERTICAL);
        sideRail.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sideRail.setPadding(Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9));
        sideRail.setBackground(Ui.rounded(0xFF081A2B, 18, this));
        panelBody.addView(tabRailScroll, new LinearLayout.LayoutParams(Ui.dp(this, 58), -1));
        tabRailScroll.addView(sideRail, new ScrollView.LayoutParams(-1, -2));
        ImageView railMark = new ImageView(this);
        railMark.setImageResource(R.drawable.santos_logo);
        railMark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        railMark.setContentDescription("Logo SANTOS — abrir Configurações");
        railMark.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        railMark.setBackground(Ui.rounded(0xFF102A42, 14, this));
        railMark.setOnClickListener(v -> selectTab(5));
        LinearLayout.LayoutParams railMarkParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 42));
        railMarkParams.bottomMargin = Ui.dp(this, 12);
        sideRail.addView(railMark, railMarkParams);
        tabViews = new View[5];
        int[] sideIcons = {R.drawable.ic_tab_touch, R.drawable.ic_tab_screen,
                R.drawable.ic_tab_optimization, R.drawable.ic_tab_logs,
                R.drawable.ic_tab_settings};
        for (int i = 0; i < sideIcons.length; i++) {
            final int page = i;
            ImageView nav = new ImageView(this);
            nav.setImageResource(sideIcons[i]);
            nav.setTag("accent_icon");
            nav.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            nav.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            nav.setContentDescription(i == 0 ? "Toque" : i == 1 ? "Tela"
                    : i == 2 ? "Otimização" : i == 3 ? "Logs" : "Configurações");
            nav.setOnClickListener(v -> selectTab(page));
            LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
            navParams.bottomMargin = Ui.dp(this, 7);
            sideRail.addView(nav, navParams);
            tabViews[i] = nav;
        }

        LinearLayout contentColumn = new LinearLayout(this);
        contentColumn.setOrientation(LinearLayout.VERTICAL);
        contentColumn.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        panelBody.addView(contentColumn, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackground(Ui.rounded(0xFF10243A, 14, this));
        header.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 4), Ui.dp(this, 3));
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        TextView brand = Ui.text(this, "SANTOS", 10, Ui.MUTED, true);
        brand.setLetterSpacing(.15f);
        heading.addView(brand, lp(-1, Ui.dp(this, 16)));
        activeTabTitle = Ui.text(this, "AJUSTES", 16, Ui.BRIGHT, true);
        heading.addView(activeTabTitle, lp(-1, Ui.dp(this, 23)));
        header.addView(heading, lp(0, Ui.dp(this, 45), 1));
        TextView badge = Ui.text(this, "LIVE", 9, 0xFF07120B, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.rounded(0xFF37D47B, 8, this));
        header.addView(badge, lp(Ui.dp(this, 39), Ui.dp(this, 27)));
        TextView min = icon("−");
        TextView close = icon("×");
        header.addView(min, lp(Ui.dp(this, 32), Ui.dp(this, 32)));
        header.addView(close, lp(Ui.dp(this, 32), Ui.dp(this, 32)));
        contentColumn.addView(header, lp(-1, Ui.dp(this, 49)));
        header.setOnTouchListener(movePanel());
        // “−” recolhe o painel para a bolinha original; não remove o flutuante.
        min.setOnClickListener(v -> {
            if (destroyed) return;
            collapsed = true;
            saveOverlayPositionState();
            resizePanel();
            updateForegroundNotification();
        });
        close.setOnClickListener(v -> closePanel());
        Ui.animatePress(min);
        Ui.animatePress(close);

        LinearLayout session = new LinearLayout(this);
        session.setGravity(Gravity.CENTER_VERTICAL);
        session.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        session.setBackground(Ui.rounded(0xFF102234, 9, this));
        TextView dot = Ui.text(this, "●", 13, Ui.SUCCESS, true);
        session.addView(dot, lp(Ui.dp(this, 24), Ui.dp(this, 26)));
        LinearLayout sessionCopy = new LinearLayout(this);
        sessionCopy.setOrientation(LinearLayout.VERTICAL);
        TextView active = Ui.text(this, "Sessão ativa", 12, Ui.WHITE, true);
        licenseStatus = Ui.text(this, "Licença: calculando dias restantes…", 9, Ui.MUTED, false); // CORRIGIDO BUG2: exibe a validade calculada em UTC.
        licenseStatus.setSingleLine(true); // CORRIGIDO BUG2: mantém o contador legível no cabeçalho.
        licenseStatus.setEllipsize(TextUtils.TruncateAt.END); // CORRIGIDO BUG2: evita estourar a largura do painel.
        sessionCopy.addView(active, lp(-1, Ui.dp(this, 17)));
        sessionCopy.addView(licenseStatus, lp(-1, Ui.dp(this, 15))); // CORRIGIDO BUG2: mostra dias restantes ao usuário.
        session.addView(sessionCopy, lp(0, Ui.dp(this, 32), 1));
        revalidateKeyButton = Ui.text(this, "Revalidar key", 8, accentForeground(panelAccentColor), true); // CORRIGIDO BUG2: adiciona ação solicitada de revalidação.
        revalidateKeyButton.setGravity(Gravity.CENTER); // CORRIGIDO BUG2: centraliza o texto do botão.
        revalidateKeyButton.setTag("accent_button");
        revalidateKeyButton.setBackground(Ui.rounded(panelAccentColor, 8, this)); // CORRIGIDO BUG2: preserva o estilo do painel.
        revalidateKeyButton.setOnClickListener(v -> revalidateKey()); // CORRIGIDO BUG2: limpa cache e valida novamente ao tocar.
        session.addView(revalidateKeyButton, lp(Ui.dp(this, 68), Ui.dp(this, 30))); // CORRIGIDO BUG2: torna o botão acessível no cabeçalho.
        contentColumn.addView(session, lp(-1, Ui.dp(this, 42)));
        refreshLicenseInfo(); // CORRIGIDO BUG2: inicializa contador e estado de acesso antes dos controles.

        // CORRIGIDO: o log foi retirado do cabeçalho e montado somente na aba LOG.
        fpsStatus = Ui.text(this,
                "FPS Live: medindo · fonte=Choreographer · painel; jogo: FPS do jogo não exposto pelo Android", 
                7, Ui.MUTED, false);
        fpsStatus.setSingleLine(true);
        fpsStatus.setEllipsize(TextUtils.TruncateAt.END);
        contentColumn.addView(fpsStatus, lp(-1, Ui.dp(this, 18)));

        pages = new FrameLayout(this);
        contentColumn.addView(pages, lp(-1, 0, 1));
        pages.addView(makeTouchPage(), pageParams());
        pages.addView(makeScreenPage(), pageParams());
        pages.addView(makeOptimizationPage(), pageParams());
        pages.addView(makeLogPage(), pageParams());
        pages.addView(makeSettingsPage(), pageParams());
        startFpsMonitor();
        selectTab(0);
        updateCapabilityStates(false); // CORRIGIDO: monta a UI sem iniciar comandos remotos antes do addView.

        // A bolha é a janela recolhida original: tocar abre o painel e arrastar
        // move o mesmo overlay, sem criar uma janela invisível adicional.
        bubble = new ImageView(this);
        bubble.setImageResource(R.drawable.floating_button);
        bubble.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bubble.setContentDescription("Bolha flutuante PAINEL SANTOS");
        bubble.setPadding(0, 0, 0, 0);
        bubble.setBackground(Ui.rounded(0xFF0D1C2C, 99, this));
        bubble.setClipToOutline(true);
        bubble.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        bubble.setOnClickListener(v -> {
            if (destroyed) return;
            collapsed = false;
            saveOverlayPositionState();
            resizePanel();
            updateForegroundNotification();
        });
        bubble.setOnTouchListener(moveBubble());
        FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(
                Ui.dp(this, BUBBLE_SIZE_DP), Ui.dp(this, BUBBLE_SIZE_DP), Gravity.CENTER);
        panel.addView(bubble, bubbleParams);
        panelBody.setVisibility(View.GONE);
        applyAccentToViews(panel, panelAccentColor, Ui.BRIGHT);

        int windowFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        panelParams = new WindowManager.LayoutParams(
                Ui.dp(this, BUBBLE_SIZE_DP), Ui.dp(this, BUBBLE_SIZE_DP), windowType(),
                windowFlags, PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.x = prefs.getInt("overlay_x", Ui.dp(this, 14));
        panelParams.y = prefs.getInt("overlay_y", Ui.dp(this, 96));
        // O modo do painel só muda depois do toque explícito em Aplicar.
        panelParams.preferredDisplayModeId = 0;
    }

    private FrameLayout.LayoutParams pageParams() { return new FrameLayout.LayoutParams(-1, -1); }

    private TextView icon(String value) {
        TextView text = Ui.text(this, value, 25, Ui.WHITE, false);
        text.setGravity(Gravity.CENTER);
        text.setBackground(Ui.rounded(0x33252A2D, 9, this));
        return text;
    }

    private ScrollView page() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        if (Build.VERSION.SDK_INT >= 21) scroll.setNestedScrollingEnabled(true);
        return scroll;
    }

    private LinearLayout content() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        // Espaço extra para o último controle não ficar sob a barra de navegação.
        list.setPadding(Ui.dp(this, 2), 0, Ui.dp(this, 2), Ui.dp(this, 30));
        return list;
    }

    private View makeTouchPage() {
        ScrollView scroll = page();
        LinearLayout list = content();
        section(list, "TOQUE");
        addToggle(list, "◎", "Resposta de toque", "Ajustes autorizados e reversíveis", "touch");
        addToggle(list, "⌁", "Touch driver", "Ajusta somente o driver de touch exposto e restaura o valor original", "touch_driver");
        addSlider(list, "Velocidade do ponteiro", "pointer_speed", -7, 7, 0,
                "Settings.System.POINTER_SPEED: -7 = lento · +7 = rápido");
        refreshPointerSliderFromSystem();
        addSlider(list, "Atraso ao manter pressionado", "long_press", 50, 1000, 400,
                "50 ms = mínimo · 1000 ms = máximo");
        scroll.addView(list);
        return scroll;
    }

    private View makeScreenPage() {
        ScrollView scroll = page();
        LinearLayout list = content();
        section(list, "TELA");
        TextView info = Ui.text(this,
                "Modos expostos por DisplayManager; renderer externo só é aplicado quando o Android oferece API confirmável.",
                10, Ui.MUTED, false);
        info.setLineSpacing(0, 1.05f);
        list.addView(info, lp(-1, Ui.dp(this, 34)));
        refreshStatus = Ui.text(this, "Taxa atual: medindo · modos expostos: medindo",
                9, Ui.BRIGHT, true);
        refreshStatus.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3));
        list.addView(refreshStatus, lp(-1, Ui.dp(this, 30)));
        addRefreshButtons(list);
        addRendererControls(list);
        scroll.addView(list);
        updateRefreshStatus();
        return scroll;
    }

    private View makeResolutionPage() {
        ScrollView scroll = page();
        LinearLayout list = content();
        section(list, "RESOLUÇÃO");
        addNotice(list, "WindowManager oficial",
                "Físico, override, efetivo, DPI, rotação e rollback são lidos antes e depois de cada alteração.");
        addToggle(list, "⛶", "Tela cheia (puxar imagem)",
                "Restaura o display físico para remover bordas pretas; não altera refresh nem usa overlay falso.",
                "fullscreen_stretch");
        addDisplayProfileControls(list);
        resolutionSave = addRestrictedAction(list, "Salvar preset Stretch Resolution (original)",
                v -> captureOriginalDisplayState());
        resolutionRestore = addRestrictedAction(list, "Restaurar preset Stretch Resolution",
                v -> restoreOriginalDisplayState());
        scroll.addView(list);
        return scroll;
    }

    private void addDisplayProfileControls(LinearLayout list) {
        section(list, "RESOLUÇÃO / DPI / TELA ESTICADA");
        displayProfileStatus = Ui.text(this,
                "Selecione resolução e DPI; Shizuku é necessário para aplicar.", 9, Ui.MUTED, false);
        displayProfileStatus.setLineSpacing(0, 1.05f);
        list.addView(displayProfileStatus, lp(-1, Ui.dp(this, 48)));

        // Os valores exibidos inicialmente são apenas fallback visual. Quando houver
        // permissão, configureDisplayProfiles() substitui tudo pelo resultado real de
        // `wm size` e `wm density` executado pelo processo remoto do Shizuku.
        try {
            Display display = wm.getDefaultDisplay();
            if (display != null) {
                android.graphics.Point real = new android.graphics.Point();
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                display.getRealSize(real);
                display.getRealMetrics(metrics);
                physicalDisplayWidth = real.x;
                physicalDisplayHeight = real.y;
                physicalDisplayDensity = metrics.densityDpi;
            }
        } catch (Throwable ignored) {
            physicalDisplayWidth = 0;
            physicalDisplayHeight = 0;
            physicalDisplayDensity = 0;
        }
        if (physicalDisplayWidth <= 0 || physicalDisplayHeight <= 0) {
            physicalDisplayWidth = getResources().getDisplayMetrics().widthPixels;
            physicalDisplayHeight = getResources().getDisplayMetrics().heightPixels;
        }
        if (physicalDisplayDensity <= 0) {
            physicalDisplayDensity = getResources().getDisplayMetrics().densityDpi;
        }
        configureDisplayProfiles(physicalDisplayWidth, physicalDisplayHeight, physicalDisplayDensity);

        resolutionSelector = spinner(this, profileLabels());
        dpiSelector = spinner(this, densityLabels());
        resolutionSelector.setSelection(Math.max(0, Math.min(resolutionProfiles.size() - 1,
                prefs.getInt("confirmed_resolution_profile", 0))));
        dpiSelector.setSelection(Math.max(0, Math.min(densityProfiles.size() - 1,
                prefs.getInt("confirmed_density_profile", 0))));
        addSpinnerRow(list, "Resolução", resolutionSelector);
        addSpinnerRow(list, "DPI", dpiSelector);
        addFlagshipPresetControl(list);
        addRestrictedAction(list, "LER wm size / wm density AGORA", v -> forceReadNativeDisplayInfo()); // CORRIGIDO BUG2: leitura remota exige key com 7+ dias.
        readNativeDisplayInfo();

        // O vídeo de referência usa dois controles independentes: Width/X 0–100
        // e Height/Y 0–10. Mover o slider só altera a seleção local; a escrita
        // do display ocorre exclusivamente no botão APLICAR abaixo.
        int savedX = Math.max(0, Math.min(100, prefs.getInt("slider_stretch_x", 0)));
        int savedY = Math.max(0, Math.min(10, prefs.getInt("slider_stretch_y", 0)));
        addSlider(list, "Alongar tela · Width/X", "stretch_x", 0, 100, savedX,
                "Width/X: 0 = padrão · 100 = limite seguro");
        stretchXSlider = sliderViews.get("stretch_x");
        addSlider(list, "Alongar tela · Height/Y", "stretch_y", 0, 10, savedY,
                "Height/Y: 0 = padrão · 10 = limite seguro");
        stretchYSlider = sliderViews.get("stretch_y");

        // Os sliders do vídeo aplicam o Stretch em tempo real após 220 ms sem
        // movimento; resolução/DPI continuam no controle explícito abaixo.
        displayApply = addRestrictedAction(list, "APLICAR resolução / DPI · Stretch em tempo real", v -> {
            applyDisplayProfileFromUi();
        });
        displayReset = addRestrictedAction(list, "RESTAURAR resolução e DPI originais", v -> restoreOriginalDisplayState()); // CORRIGIDO BUG2: restauração de display fica restrita abaixo de 7 dias.
        displayApply.setEnabled(false);
        displayApply.setAlpha(.52f);
        displayReset.setEnabled(false);
        displayReset.setAlpha(.52f);
        // CORRIGIDO ALONGAR TELA: não existe botão nem comando wm overscan.
    }

    private void addFlagshipPresetControl(LinearLayout list) {
        section(list, "PRESETS FLAGSHIP");
        addNotice(list, "Resoluções de celulares gamers",
                "Selecione um preset; a aplicação usa o controle existente APLICAR resolução / DPI e nunca altera refresh automaticamente.");
        flagshipPresets.clear();
        flagshipPresets.add(new FlagshipPreset("ASUS ROG Phone 10 Ultimate", 2400, 1080));
        flagshipPresets.add(new FlagshipPreset("RedMagic 11 Pro", 2688, 1216));
        flagshipPresets.add(new FlagshipPreset("iPhone 16 Pro Max", 2868, 1320));
        flagshipPresets.add(new FlagshipPreset("Samsung Galaxy S25 Ultra", 3120, 1440));
        flagshipPresets.add(new FlagshipPreset("iQOO 13", 3168, 1440));
        flagshipPresets.add(new FlagshipPreset("iQOO 15 Pro", 3168, 1440));
        flagshipPresets.add(new FlagshipPreset("OnePlus 12", 3168, 1440));
        flagshipPresets.add(new FlagshipPreset("OnePlus 15", 2772, 1272));
        flagshipPresets.add(new FlagshipPreset("Xiaomi 14T", 2712, 1220));
        flagshipPresets.add(new FlagshipPreset("Xiaomi 14T Pro", 2712, 1220));
        flagshipPresets.add(new FlagshipPreset("Black Shark 6 Pro", 2400, 1080));
        flagshipPresets.add(new FlagshipPreset("Poco X7 Pro 5G", 2712, 1220));
        flagshipPresets.add(new FlagshipPreset("Poco F7 / F7 GT", 2772, 1280));
        flagshipPresets.add(new FlagshipPreset("Redmi Note 14 Pro 5G", 2712, 1220));
        flagshipPresets.add(new FlagshipPreset("Infinix GT 30 Pro", 2720, 1224));
        flagshipPresets.add(new FlagshipPreset("Samsung Galaxy A56 5G", 2340, 1080));
        flagshipPresets.add(new FlagshipPreset("Moto G75 5G", 2388, 1080));
        flagshipPresets.add(new FlagshipPreset("Moto G85 5G", 2400, 1080));
        flagshipPresets.add(new FlagshipPreset("Realme GT 6", 2780, 1264));
        flagshipPresets.add(new FlagshipPreset("Realme GT Neo 6", 2780, 1264));
        String[] labels = new String[flagshipPresets.size() + 1];
        labels[0] = "Selecionar preset flagship";
        for (int i = 0; i < flagshipPresets.size(); i++) labels[i + 1] = flagshipPresets.get(i).label();
        flagshipSelector = spinner(this, labels);
        flagshipSelector.setSelection(Math.max(0, Math.min(labels.length - 1,
                prefs.getInt("confirmed_flagship_position", 0))));
        addSpinnerRow(list, "Preset", flagshipSelector);
    }

    private FlagshipPreset selectedFlagshipPreset() {
        if (flagshipSelector == null) return null;
        int position = flagshipSelector.getSelectedItemPosition() - 1;
        return position >= 0 && position < flagshipPresets.size()
                ? flagshipPresets.get(position) : null;
    }

    private int[] flagshipTarget(FlagshipPreset preset) {
        int portraitWidth = Math.min(physicalDisplayWidth, physicalDisplayHeight);
        int portraitHeight = Math.max(physicalDisplayWidth, physicalDisplayHeight);
        if (portraitWidth <= 0 || portraitHeight <= 0 || preset == null) return new int[]{0, 0};
        int rotation = currentDisplayRotation;
        if (rotation < 0) {
            Display display = primaryDisplay();
            rotation = display == null ? Surface.ROTATION_0 : display.getRotation();
        }
        boolean landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int width = landscape ? preset.officialHeight : preset.officialWidth;
        int height = landscape ? preset.officialWidth : preset.officialHeight;
        Log.d("RESOLUTION_DEBUG", "FLAGSHIP_TARGET orientation=" + (landscape ? "landscape" : "portrait")
                + " target=" + width + "x" + height + " physical=" + portraitWidth + "x" + portraitHeight);
        return new int[]{width, height};
    }
    private void configureDisplayProfiles(int width, int height, int density) {
        if (width <= 0 || height <= 0 || density <= 0) return;
        physicalDisplayWidth = width;
        physicalDisplayHeight = height;
        physicalDisplayDensity = Math.max(1, Math.min(1000, density));
        resolutionProfiles.clear();
        resolutionProfiles.add(new DisplayProfile("Nativa • " + width + " x " + height, width, height));
        resolutionProfiles.add(new DisplayProfile("90% seguro • " + even(width * .90f) + " x " + even(height * .90f),
                even(width * .90f), even(height * .90f)));
        resolutionProfiles.add(new DisplayProfile("80% seguro • " + even(width * .80f) + " x " + even(height * .80f),
                even(width * .80f), even(height * .80f)));
        densityProfiles.clear();
        addUniqueDensity(physicalDisplayDensity);
        addUniqueDensity(even(physicalDisplayDensity * .90f));
        addUniqueDensity(even(physicalDisplayDensity * .80f));
    }

    private int[] parseDisplaySize(String value) {
        if (value == null || !value.matches("\\d{1,6}x\\d{1,6}")) return new int[]{0, 0};
        String[] parts = value.split("x", 2);
        try { return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])}; }
        catch (NumberFormatException ignored) { return new int[]{0, 0}; }
    }

    private void forceReadNativeDisplayInfo() {
        if (!hasActiveLicense()) { // CORRIGIDO: somente key válida e não expirada bloqueia o caminho remoto.
            postMessage(false, "Ative uma key válida antes de ler wm size/wm density"); // CORRIGIDO: mensagem não promete bloqueio artificial de 7 dias.
            return; // CORRIGIDO: impede comando sem sessão de licença.
        }
        nativeDisplayInfoLoaded = false;
        nativeDisplayInfoRequested = false;
        nextNativeDisplayInfoAttemptAt = 0L;
        // CORRIGIDO: a ponte verifica autorização no worker; não usar snapshot
        // antigo da Main Thread para cancelar a leitura explicitamente solicitada.
        readNativeDisplayInfo(true);
    }

    private void readNativeDisplayInfo() {
        readNativeDisplayInfo(ShizukuBridge.hasPermission());
    }

    private void readNativeDisplayInfo(boolean authorized) {
        if (destroyed) return;
        if (!authorized || !hasActiveLicense()) { // CORRIGIDO: autorização Shizuku e key ativa são os únicos gates da leitura.
            nativeDisplayInfoRequested = false; // CORRIGIDO: permite nova tentativa quando Shizuku/key ficarem prontos.
            nativeDisplayInfoLoaded = false; // CORRIGIDO: não mantém estado de uma sessão anterior.
            return; // CORRIGIDO: não envia comando sem autorização ou licença ativa.
        } // CORRIGIDO: removido o bloqueio extra pela ponte manual e por 7 dias.
        if (nativeDisplayInfoRequested || nativeDisplayInfoLoaded) return;
        if (SystemClock.uptimeMillis() < nextNativeDisplayInfoAttemptAt) return;
        nativeDisplayInfoRequested = true;
        ShizukuBridge.readDisplayInfo((ok, msg, info) -> {
            if (destroyed) {
                // Liberar o marcador mesmo quando a resposta chega depois do
                // lifecycle da overlay; nenhuma View é tocada neste caminho.
                nativeDisplayInfoRequested = false;
                return;
            }
            if (!hasActiveLicense()) { // CORRIGIDO: descarta resposta se a key for invalidada durante a operação.
                nativeDisplayInfoRequested = false; // CORRIGIDO: não mantém callback pendente.
                nativeDisplayInfoLoaded = false; // CORRIGIDO: remove snapshot de sessão inválida.
                return; // CORRIGIDO: não toca Spinners/Views sem licença ativa.
            } // CORRIGIDO: callback continua protegido contra expiração.
            if (!ok || info == null) {
                nativeDisplayInfoRequested = false;
                nativeDisplayInfoLoaded = false;
                nextNativeDisplayInfoAttemptAt = SystemClock.uptimeMillis() + 10_000L;
                postMessage(false, msg);
                return;
            }
            int[] nativeSize = parseDisplaySize(info.physicalSize);
            if (nativeSize[0] <= 0 || nativeSize[1] <= 0 || info.physicalDensity <= 0) {
                // CORRIGIDO: permitir nova tentativa após uma resposta inválida;
                // antes nativeDisplayInfoRequested ficava preso em true.
                nativeDisplayInfoRequested = false;
                nativeDisplayInfoLoaded = false;
                nextNativeDisplayInfoAttemptAt = SystemClock.uptimeMillis() + 10_000L;
                postMessage(false, "wm size/wm density retornaram valores inválidos");
                return;
            }
            mainHandler.post(() -> {
                if (destroyed) return;
                configureDisplayProfiles(nativeSize[0], nativeSize[1], info.physicalDensity);
                // ADICIONADO: manter a rotação lida pelo comando remoto.
                currentDisplayRotation = info.rotation;
                // ADICIONADO: o botão de overscan depende da resposta real do firmware.
                overscanSupported = info.overscanSupported;
                if (resolutionSelector != null && dpiSelector != null) {
                    ArrayAdapter<String> resolutions = (ArrayAdapter<String>) resolutionSelector.getAdapter();
                    ArrayAdapter<String> densities = (ArrayAdapter<String>) dpiSelector.getAdapter();
                    resolutions.clear();
                    resolutions.addAll(profileLabels());
                    densities.clear();
                    densities.addAll(densityLabels());
                    resolutions.notifyDataSetChanged();
                    densities.notifyDataSetChanged();
                }
                String override = info.overrideSize.isEmpty() ? "sem override" : info.overrideSize;
                String effective = info.currentSize == null || info.currentSize.isEmpty()
                        ? info.physicalSize : info.currentSize;
                if (displayProfileStatus != null) {
                    displayProfileStatus.setText("Físico: " + info.physicalSize + " @ "
                            + info.physicalDensity + " dpi\nOverride: " + override
                            + " · efetivo: " + effective + " @ " + info.currentDensity
                            + " dpi · rotação: " + info.rotation);
                }
                nativeDisplayInfoRequested = false;
                nativeDisplayInfoLoaded = true;
                updateCapabilityStates();
            });
        });
    }

    private int even(float value) {
        int result = Math.max(2, Math.round(value));
        return result % 2 == 0 ? result : result - 1;
    }

    private void addUniqueDensity(int density) {
        int safe = Math.max(1, Math.min(1000, density));
        if (!densityProfiles.contains(safe)) densityProfiles.add(safe);
    }

    private String[] profileLabels() {
        String[] labels = new String[resolutionProfiles.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = resolutionProfiles.get(i).label;
        return labels;
    }

    private String[] densityLabels() {
        String[] labels = new String[densityProfiles.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = densityProfiles.get(i) + " dpi";
        return labels;
    }

    private Spinner spinner(Context context, String[] values) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(Ui.rounded(0xFF13283D, 7, this));
        return spinner;
    }

    private void addSpinnerRow(LinearLayout list, String label, Spinner spinner) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 9), Ui.dp(this, 3), Ui.dp(this, 5), Ui.dp(this, 3));
        row.setBackground(Ui.rounded(0xFF102234, 9, this));
        TextView text = Ui.text(this, label, 10, Ui.WHITE, true);
        row.addView(text, lp(0, Ui.dp(this, 38), 1));
        row.addView(spinner, lp(Ui.dp(this, 150), Ui.dp(this, 38)));
        LinearLayout.LayoutParams params = lp(-1, Ui.dp(this, 44));
        params.bottomMargin = Ui.dp(this, 5);
        list.addView(row, params);
    }

    // O vídeo usa controles independentes Width/X 0–100 e Height/Y 0–10.
    // O mapeamento reduz cada eixo somente dentro do tamanho físico confirmado;
    // assim nenhum valor ultrapassa o limite seguro do WindowManager.
    private int[] stretchedAxesPhysicalSize(int widthValue, int heightValue) {
        int baseWidth = Math.min(physicalDisplayWidth, physicalDisplayHeight);
        int baseHeight = Math.max(physicalDisplayWidth, physicalDisplayHeight);
        if (baseWidth <= 0 || baseHeight <= 0) return new int[]{0, 0};
        // CORRIGIDO: o perfil combinado preserva toda a largura física e só
        // reduz a altura; X/Y continuam sendo preview local monotônico.
        float amount = Math.max(widthValue / 100f, heightValue / 10f);
        int targetHeight = even(baseHeight * (1f - .50f * amount));
        return new int[]{baseWidth, Math.max(1, Math.min(baseHeight, targetHeight))};
    }

    private void applyAlongarPreset(final DisplayManagerHelper.Preset preset) {
        if (destroyed || pendingToggleOperations.contains("alongar_preset")) return;
        if (!pendingToggleOperations.isEmpty() && !pendingToggleOperations.contains("safe_mode")) {
            message(null, "Outra função está sendo aplicada; aguarde o resultado no LOG");
            return;
        }
        pendingToggleOperations.add("alongar_preset");
        saveOverlayDisplaySnapshot();
        message("Aplicando " + preset.name() + " · preset Stretch Resolution…");
        DisplayManagerHelper.applyStretchPreset(this, preset, 1.00f, (ok, msg) -> {
            if (destroyed) return;
            pendingToggleOperations.remove("alongar_preset");
            if (ok) {
                prefs.edit().putString("stretch_preset_name", "Stretch Resolution")
                        .putInt("stretch_preset_version", 1)
                        .putBoolean("stretch_requested", preset == DisplayManagerHelper.Preset.STRETCHED)
                        .apply();
                if (preset == DisplayManagerHelper.Preset.DEFAULT) restoreStretchSliderPreset();
                if (preset == DisplayManagerHelper.Preset.FULLSCREEN
                        || preset == DisplayManagerHelper.Preset.DEFAULT) {
                    setFeatureState("fullscreen_stretch", preset == DisplayManagerHelper.Preset.FULLSCREEN);
                    setToggleChecked("fullscreen_stretch", preset == DisplayManagerHelper.Preset.FULLSCREEN);
                }
                if (displayProfileStatus != null) displayProfileStatus.setText(msg);
            } else {
                restoreOverlayDisplaySnapshot();
                if (preset == DisplayManagerHelper.Preset.FULLSCREEN
                        || preset == DisplayManagerHelper.Preset.DEFAULT) {
                    setToggleChecked("fullscreen_stretch",
                            prefs.getBoolean("feature_fullscreen_stretch", false));
                }
            }
            postMessage(ok, msg);
            updateCapabilityStates();
        });
    }

    private void scheduleLiveStretchApply() {
        if (destroyed) return;
        if (liveStretchApply != null) mainHandler.removeCallbacks(liveStretchApply);
        final long generation = ++stretchPreviewGeneration;
        liveStretchApply = new Runnable() {
            @Override public void run() {
                if (destroyed || generation != stretchPreviewGeneration) return;
                if (!pendingToggleOperations.isEmpty()) {
                    mainHandler.postDelayed(this, 300L);
                    return;
                }
                liveStretchApply = null;
                Log.d("DISPLAY_DEBUG", "STRETCH_PREVIEW_LAST_VALUE_APPLY");
                applyDisplayProfileFromUi();
            }
        };
        // CORRIGIDO: preview durante o movimento; somente o último valor
        // completo é aplicado 300 ms após onStopTrackingTouch.
        mainHandler.postDelayed(liveStretchApply, 300L);
    }

    private void applyDisplayProfileFromUi() {
        if (!beginRemoteOperation("display_profile")) return;
        if (resolutionProfiles.isEmpty() || densityProfiles.isEmpty()) {
            finishRemoteOperation("display_profile");
            message("Resolução nativa ainda não foi lida por wm size");
            return;
        }
        final FlagshipPreset flagship = selectedFlagshipPreset();
        final boolean flagshipMode = flagship != null;
        final int[] selectedFlagshipSize = flagshipMode ? flagshipTarget(flagship) : new int[]{0, 0};
        if (flagshipMode && (selectedFlagshipSize[0] <= 0 || selectedFlagshipSize[1] <= 0)) {
            finishRemoteOperation("display_profile");
            postMessage(false, "PRESETS FLAGSHIP: orientação/display físico inválido");
            return;
        }
        DisplayProfile profile = resolutionProfiles.get(Math.max(0,
                Math.min(resolutionProfiles.size() - 1, resolutionSelector.getSelectedItemPosition())));
        int selectedDensity = densityProfiles.get(Math.max(0,
                Math.min(densityProfiles.size() - 1, dpiSelector.getSelectedItemPosition())));
        int widthValue = stretchXSlider == null ? prefs.getInt("slider_stretch_x", 0)
                : stretchXSlider.getProgress();
        int heightValue = stretchYSlider == null ? prefs.getInt("slider_stretch_y", 0)
                : stretchYSlider.getProgress();
        final int requestedX = Math.max(0, Math.min(100, widthValue));
        final int requestedY = Math.max(0, Math.min(10, heightValue));
        final boolean stretch = flagshipMode || requestedX > 0 || requestedY > 0;
        final int[] size = flagshipMode ? selectedFlagshipSize
                : (stretch ? stretchedAxesPhysicalSize(requestedX, requestedY)
                : new int[]{profile.width, profile.height});
        if (size[0] <= 0 || size[1] <= 0) {
            finishRemoteOperation("display_profile");
            postMessage(false, "Stretch Resolution: display físico não lido");
            return;
        }
        final int density = flagshipMode ? 0 : (stretch
                ? Math.max(1, Math.min(1000, Math.round(physicalDisplayDensity
                * size[1] / (float) Math.max(1, Math.max(physicalDisplayWidth, physicalDisplayHeight)))))
                : Math.max(1, Math.min(1000, selectedDensity)));
        final String modeLabel = flagshipMode ? flagship.name : "Stretch Resolution";
        final long generation = ++displayOperationGeneration;
        saveOverlayDisplaySnapshot();
        Log.d("DISPLAY_DEBUG", "RESOLUTION_APPLY_REQUEST mode=" + modeLabel
                + " x=" + requestedX + " y=" + requestedY + " target=" + size[0] + "x" + size[1]
                + " density=" + (flagshipMode ? "auto" : density));
        DisplayManagerHelper.Callback profileCallback = (ok, msg) -> {
            if (destroyed || generation != displayOperationGeneration) return;
            if (ok) {
                prefs.edit().putInt("stretch_preset_x", requestedX)
                        .putInt("stretch_preset_y", requestedY)
                        .putInt("slider_stretch_x", requestedX)
                        .putInt("slider_stretch_y", requestedY)
                        .putInt("confirmed_resolution_profile",
                                resolutionSelector == null ? 0 : resolutionSelector.getSelectedItemPosition())
                        .putInt("confirmed_density_profile",
                                dpiSelector == null ? 0 : dpiSelector.getSelectedItemPosition())
                        .putInt("confirmed_flagship_position",
                                flagshipSelector == null ? 0 : flagshipSelector.getSelectedItemPosition())
                        .putInt("confirmed_display_width", size[0])
                        .putInt("confirmed_display_height", size[1])
                        .putInt("confirmed_display_density", density)
                        .putString("stretch_preset_name", modeLabel)
                        .putInt("stretch_preset_version", 1)
                        .putBoolean("stretch_requested", stretch)
                        .apply();
                if (displayProfileStatus != null) displayProfileStatus.setText(
                        modeLabel + " confirmado: " + size[0] + " x " + size[1]
                                + (flagshipMode ? " · DPI automático confirmado" : " @ " + density + " dpi")
                                + "\n" + msg);
                postMessage(true, modeLabel + " confirmado por readback: " + msg);
            } else {
                restoreOverlayDisplaySnapshot();
                postMessage(false, msg + " · resolução não confirmada; rollback mantido");
            }
            finishRemoteOperation("display_profile");
            mainHandler.post(this::refreshPanelAfterDisplayChange);
        };
        if (flagshipMode) {
            DisplayManagerHelper.applyFlagshipProfile(this, flagship.officialHeight,
                    flagship.officialWidth, profileCallback);
        } else {
            DisplayManagerHelper.applyCustomProfile(this, size[0], size[1], density, stretch,
                    profileCallback);
        }
    }
    private void refreshPanelAfterDisplayChange() {
        if (destroyed) return;
        try {
            if (panel != null && panelBody != null && bubble != null
                    && panelParams != null && wm != null && panel.isAttachedToWindow()) {
                resizePanel();
            }
            updateCapabilityStates();
        } catch (Throwable error) {
            // A mudança de wm size pode invalidar uma janela por alguns ms;
            // jamais deixar essa corrida matar o serviço/painel.
            Log.e(FLOW_TAG, "DISPLAY_PANEL_REFRESH_FAILED", error);
        }
    }

    private int[] refreshTargets(Display.Mode[] modes) {
        List<Integer> targets = new ArrayList<>();
        for (Display.Mode mode : modes) {
            if (mode == null) continue;
            int rounded = Math.round(mode.getRefreshRate());
            if (rounded > 0 && !targets.contains(rounded)) targets.add(rounded);
        }
        int[] result = new int[targets.size()];
        for (int i = 0; i < targets.size(); i++) result[i] = targets.get(i);
        return result;
    }

    private void addRefreshButtons(LinearLayout list) {
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 4));
        Display.Mode[] modes = supportedModes();
        int[] fixedRates = refreshTargets(modes);
        int current = Math.round(primaryDisplay() == null ? 0f : primaryDisplay().getRefreshRate());
        int savedRefresh = prefs.getInt("selected_refresh_hz", -1);
        selectedRefreshHz = -1;
        refreshChoices.clear();
        refreshSupport.clear();
        StringBuilder availability = new StringBuilder();
        for (int target : fixedRates) {
            boolean supported = hasModeNear(modes, target);
            refreshSupport.put(target, supported);
            if (supported && selectedRefreshHz < 0
                    && Math.abs(current - target) <= 2) {
                selectedRefreshHz = target;
            }
            TextView choice = Ui.text(this, target + " Hz", 10, Ui.WHITE, true);
            choice.setGravity(Gravity.CENTER);
            choice.setEnabled(supported);
            choice.setAlpha(supported ? 1f : .42f);
            choice.setContentDescription(target + " Hz: "
                    + (supported ? "disponível neste display" : "indisponível neste dispositivo"));
            choice.setBackground(Ui.rounded(0xFF13283D, 8, this));
            final int chosen = target;
            choice.setOnClickListener(v -> {
                if (!refreshSupport.getOrDefault(chosen, false)) return;
                selectedRefreshHz = chosen;
                updateRefreshChoices();
                if (refreshApply != null) {
                    refreshApply.setText("Aplicar modo " + chosen + " Hz");
                    boolean authorized = hasActiveLicense();
                    refreshApply.setEnabled(authorized);
                    refreshApply.setAlpha(authorized ? 1f : .52f);
                }
                message("Modo real " + chosen + " Hz selecionado; escrita aguarda Aplicar");
            });
            refreshChoices.put(target, choice);
            row.addView(choice, lp(Ui.dp(this, 64), Ui.dp(this, 32)));
            if (availability.length() > 0) availability.append(" · ");
            availability.append(target).append(" Hz: ")
                    .append(supported ? "disponível" : "indisponível");
        }
        if (selectedRefreshHz < 0) {
            for (int target : fixedRates) {
                if (target == savedRefresh && hasModeNear(modes, target)) {
                    selectedRefreshHz = target;
                    break;
                }
            }
        }
        horizontal.addView(row);
        list.addView(horizontal, lp(-1, Ui.dp(this, 38)));
        TextView supportInfo = Ui.text(this,
                availability + " · Sistema: peak/min_refresh_rate · Painel: preferredDisplayModeId · Jogo externo: API não exposta",
                8, Ui.MUTED, false);
        supportInfo.setLineSpacing(0, 1.05f);
        list.addView(supportInfo, lp(-1, Ui.dp(this, 30)));
        refreshApply = addRestrictedAction(list, "Selecione um modo suportado para aplicar", v -> {
            if (selectedRefreshHz >= 0 && refreshSupport.getOrDefault(selectedRefreshHz, false)) {
                applyRefresh(selectedRefreshHz);
            }
        });
        refreshApply.setEnabled(selectedRefreshHz >= 0 && refreshSupport.getOrDefault(selectedRefreshHz, false));
        refreshApply.setAlpha(refreshApply.isEnabled() ? 1f : .55f);
        refreshRestore = addRestrictedAction(list, "RESTAURAR Hz ORIGINAL", v -> {
            if (!beginRemoteOperation("refresh_restore")) return;
            ShellManager.restoreGlobalRefreshRate(this, (ok, msg) -> {
                if (destroyed) return;
                boolean modeOk = ok && restoreOriginalPanelDisplayMode();
                finishRemoteOperation("refresh_restore");
                postMessage(ok && modeOk, modeOk ? msg + " · Display Mode original confirmado" : msg);
            });
        });
    }

    private Display primaryDisplay() {
        try {
            DisplayManager manager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) return display;
        } catch (Throwable error) {
            Log.w("OverlayService", "Falha lendo display principal", error);
        }
        return wm == null ? null : wm.getDefaultDisplay();
    }

    private Display.Mode[] supportedModes() {
        try {
            Display display = primaryDisplay();
            if (Build.VERSION.SDK_INT >= 23 && display != null) {
                Display.Mode[] modes = display.getSupportedModes();
                if (modes != null && modes.length > 0) return modes;
                Display.Mode current = display.getMode();
                return current == null ? new Display.Mode[0] : new Display.Mode[]{current};
            }
        } catch (Throwable error) {
            Log.w("OverlayService", "Falha lendo modos de refresh", error);
        }
        return new Display.Mode[0];
    }

    private boolean hasModeNear(Display.Mode[] modes, int target) {
        for (Display.Mode mode : modes) {
            // 59.94/89.91 Hz são exibidos como 60/90 sem inventar um modo.
            if (Math.abs(mode.getRefreshRate() - target) <= 2.5f) return true;
        }
        return false;
    }

    private void refreshDisplayModes() {
        Display.Mode[] modes = supportedModes();
        for (Map.Entry<Integer, TextView> entry : refreshChoices.entrySet()) {
            boolean supported = hasModeNear(modes, entry.getKey());
            refreshSupport.put(entry.getKey(), supported);
            entry.getValue().setEnabled(supported && pendingToggleOperations.isEmpty());
            entry.getValue().setAlpha(supported ? 1f : .42f);
        }
        if (selectedRefreshHz >= 0 && !hasModeNear(modes, selectedRefreshHz)) {
            selectedRefreshHz = Math.round(primaryDisplay() == null ? 0f : primaryDisplay().getRefreshRate());
        }
        updateRefreshChoices();
        Log.d("DISPLAY_DEBUG", "REFRESH_MODES_READBACK " + supportedModeText());
    }

    private void updateRefreshChoices() {
        for (Map.Entry<Integer, TextView> entry : refreshChoices.entrySet()) {
            boolean selected = entry.getKey() == selectedRefreshHz;
            TextView choice = entry.getValue();
            boolean supported = refreshSupport.getOrDefault(entry.getKey(), false);
            choice.setTextColor(supported ? (selected ? accentForeground(panelAccentColor) : panelAccentColor) : 0xFF6B7072);
            choice.setAlpha(supported ? 1f : .42f);
            choice.setBackground(Ui.rounded(selected && supported ? panelAccentColor : 0xFF13283D, 8, this));
        }
    }

    private void updateRefreshStatus() {
        if (destroyed || refreshStatus == null) return;
        String applied = prefs.contains("applied_display_rate")
                ? String.format(Locale.US, "%.0f Hz", prefs.getFloat("applied_display_rate", 0f))
                : "nenhum";
        refreshStatus.setText("Taxa atual: " + displayRefreshText()
                + " · modos expostos: " + supportedModeText()
                + " · modo aplicado ao painel: " + applied);
    }

    private String supportedModeText() {
        Display.Mode[] modes = supportedModes();
        StringBuilder text = new StringBuilder();
        for (Display.Mode mode : modes) {
            int rounded = Math.round(mode.getRefreshRate());
            if (rounded <= 0) continue;
            String value = rounded + " Hz";
            if (text.indexOf(value) < 0) {
                if (text.length() > 0) text.append(", ");
                text.append(value);
            }
        }
        return text.length() == 0 ? "não informado" : text.toString();
    }

    private View makeOptimizationPage() {
        ScrollView scroll = page();
        LinearLayout list = content();
        section(list, "OTIMIZAÇÃO");
        addToggle(list, "♨", "DESATIVAR TODOS OS TWEAKS",
                "Desliga boosts e otimizações ativos e restaura os valores salvos; não reativa nada ao desligar.",
                "safe_mode");
        addToggle(list, "☷", "Sistema responsivo", "Escalas de animação reversíveis, somente por ação explícita", "system");
        addSlider(list, "Escalas de animação", "animation_scale", 0, 200, 100,
                "Window/transition/animator duration scale: 0.0x a 2.0x");
        section(list, "DESEMPENHO / MEMÓRIA");
        addNotice(list, "Ações pontuais",
                "Cache e processos são tratados somente por APIs/comandos confirmáveis; o LOG mostra saída, erro e readback.");
        addRestrictedAction(list, "LIMPAR CACHE AGORA", v -> runCacheAction());
        addRestrictedAction(list, "LIBERAR RAM AGORA", v -> runRamAction());
        addToggle(list, "◈", "CPU Performance", "modo de desempenho fixo somente se o Android confirmar; restaura o snapshot", "cpu_governor");
        addToggle(list, "◆", "GPU Turbo", "Frequência máxima somente se o driver expuser o limite", "gpu_turbo");
        addToggle(list, "⇄", "Buffer TCP", "Otimiza tcp_rmem/tcp_wmem; valores originais são preservados", "network");
        section(list, "JOGO EM FOCO");
        addToggle(list, "▶", "Game Mode oficial", "Usa cmd game mode performance somente quando o Android expõe suporte; restaura o modo anterior", "game_mode");
        addToggle(list, "◍", "Doze Blocker", "Whitelist temporária do pacote do jogo em foco", "doze_blocker");
        addToggle(list, "♨", "Thermal", "Não suportado: a proteção térmica nunca é desativada pelo painel", "thermal");
        section(list, "RESTAURAÇÃO");
        addRestrictedAction(list, "RESTAURAR TUDO AO PADRÃO", v -> restoreManagedPreferences());
        scroll.addView(list);
        return scroll;
    }

    private View makeSettingsPage() {
        ScrollView scroll = page();
        LinearLayout list = content();
        section(list, "CONFIGURAÇÕES");
        addShizukuCard(list);
        addGamePackageControl(list);
        addAxManagerControls(list);
        addToggle(list, "⚡", "Ativar via Brevent",
                "Instale Brevent → abra → ative via Wireless Debugging → volte e toque no switch. Sem API pública de shell externo, o painel usa WRITE_SECURE_SETTINGS permanente quando já concedido.",
                "brevent");
        addNotice(list, "Caminhos de concessão",
                "Shizuku: Autorizar/Testar ponte · Brevent: abrir somente se instalado · ADB: pm grant painel.sensi.santos android.permission.WRITE_SECURE_SETTINGS",
                Ui.MUTED);
        addToggle(list, "◌", "Ocultar na transmissão",
                "Tira o painel da tela durante a transmissão; o jogo continua visível e a captura não é bloqueada.",
                "hide_for_capture");
        addToggle(list, "☾", "Não Perturbe",
                "Suprime pop-ups, visualização e vibração de notificações sem alterar o volume de mídia/jogo; pode ser parcial se a política não permitir.",
                "do_not_disturb");
        section(list, "PERMISSÕES / DIAGNÓSTICO");
        addNotice(list, "Status", permissionStatus(), Ui.MUTED);
        addColorPicker(list);
        deviceInfo = Ui.text(this, "Lendo informações do dispositivo…", 8, Ui.MUTED, false);
        deviceInfo.setLineSpacing(0, 1.05f);
        list.addView(deviceInfo, lp(-1, Ui.dp(this, 210)));
        addNotice(list, "Renderer externo", "O Android não expõe API package-scoped confirmável para trocar o renderer de outro jogo; nenhuma escrita fictícia é feita.", Ui.MUTED);
        addNotice(list, "Aplicativo", "PAINEL SANTOS · painel.sensi.santos · 1.0.8 (9)", Ui.BRIGHT);
        addNotice(list, "Acesso", "Instagram: @davirosy2 · TikTok: @davirosy2", Ui.BRIGHT);
        scroll.addView(list);
        refreshDeviceInfo();
        return scroll;
    }

    private void addAxManagerControls(LinearLayout list) {
        section(list, "AXMANAGER · CONFIGURAÇÕES");
        addNotice(list, "Status real do modo aplicado",
                "Agressivo: sem governor/tweak inventado; Balanceado: sem API legítima exposta; Econômico/Seguro: preserva a proteção do Android. Nenhum modo é confirmado sem readback real.", Ui.MUTED);
        axModeStatus = Ui.text(this,
                "Modo aplicado: não confirmado · fonte=API pública Android",
                9, Ui.BRIGHT, false);
        axModeStatus.setLineSpacing(0, 1.05f);
        list.addView(axModeStatus, lp(-1, Ui.dp(this, 34)));
        addAction(list, "MODO AGRESSIVO", v -> applyAxMode("Agressivo"));
        addAction(list, "MODO BALANCEADO", v -> applyAxMode("Balanceado"));
        addAction(list, "MODO ECONÔMICO/SEGURO", v -> applyAxMode("Econômico/Seguro"));
        addAction(list, "RESTAURAR MODO ANTERIOR", v -> restoreAxMode());
    }

    private void applyAxMode(String mode) {
        if (destroyed) return;
        String detail = "AXManager " + mode + " não aplicado: não suportado pelo Android; nenhuma API legítima de modo exposta";
        if (axModeStatus != null) axModeStatus.setText("Modo aplicado: não confirmado · " + detail);
        postMessage(false, detail);
    }

    private void restoreAxMode() {
        if (destroyed) return;
        String detail = "Modo anterior AXManager não restaurado: não suportado pelo Android; nenhum snapshot/escrita foi executado";
        if (axModeStatus != null) axModeStatus.setText("Modo aplicado: não confirmado · " + detail);
        postMessage(false, detail);
    }

    private void addColorPicker(LinearLayout list) {
        section(list, "COR DE DESTAQUE");
        colorWheel = new ColorWheelView(this);
        colorWheel.setColor(panelAccentColor);
        colorWheel.setContentDescription("Roda de seleção da cor de destaque do painel");
        list.addView(colorWheel, lp(-1, Ui.dp(this, 156)));
        colorStatus = Ui.text(this, "Cor confirmada: #" + String.format(Locale.US, "%06X", panelAccentColor & 0xFFFFFF),
                9, panelAccentColor, true);
        colorStatus.setGravity(Gravity.CENTER_VERTICAL);
        list.addView(colorStatus, lp(-1, Ui.dp(this, 26)));
        colorWheel.setOnColorChangedListener(color -> {
            if (destroyed) return;
            applyAccentColor(color);
            if (colorStatus != null) colorStatus.setText("Cor confirmada: #"
                    + String.format(Locale.US, "%06X", color & 0xFFFFFF));
            postMessage(true, "Cor do painel selecionada e aplicada: #"
                    + String.format(Locale.US, "%06X", color & 0xFFFFFF));
        });
    }

    // O LOG tem uma linha-resumo expansível, não um botão de ação. O único
    // botão de ação desta aba é LIMPAR LOG.
    private View makeLogPage() {
        ScrollView scroll = page();
        LinearLayout list = content();
        section(list, "LOG");
        addAction(list, "LIMPAR LOG", v -> clearLog());

        logSummary = Ui.text(this, "Shizuku: aguardando · Overlay: pendente ▾",
                9, Ui.BRIGHT, true);
        logSummary.setGravity(Gravity.CENTER_VERTICAL);
        logSummary.setSingleLine(true);
        logSummary.setEllipsize(null);
        logSummary.setPadding(Ui.dp(this, 9), 0, Ui.dp(this, 9), 0);
        logSummary.setBackground(Ui.rounded(0xFF102234, 9, this));
        logSummary.setContentDescription("Expandir ou recolher o LOG");
        logSummary.setOnClickListener(v -> toggleLog());
        list.addView(logSummary, lp(-1, Ui.dp(this, 38)));

        status = Ui.text(this, "", 7, 0xFFEDE7F0, false);
        status.setSingleLine(false);
        status.setMinLines(4);
        status.setMaxLines(Integer.MAX_VALUE);
        status.setEllipsize(null);
        status.setHorizontallyScrolling(false);
        status.setVerticalScrollBarEnabled(true);
        status.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        status.setMovementMethod(new ScrollingMovementMethod());
        status.setTextIsSelectable(true);
        status.setTextColor(0xFFEDE7F0);
        status.setBackground(Ui.rounded(0xCC07121F, 8, this));
        status.setPadding(Ui.dp(this, 5), Ui.dp(this, 4), Ui.dp(this, 5), Ui.dp(this, 4));
        status.setMaxHeight(Ui.dp(this, 300));
        status.setVisibility(View.GONE);
        list.addView(status, lp(-1, Ui.dp(this, 300)));
        logExpanded = false;
        appendStatusLine(null, "Shizuku: aguardando · Overlay: pendente\n"
                + "Último comando: nenhum · exit code: —");
        scroll.addView(list);
        return scroll;
    }

    private void toggleLog() {
        if (destroyed || status == null) return;
        logExpanded = !logExpanded;
        status.setVisibility(logExpanded ? View.VISIBLE : View.GONE);
        updateLogSummary();
        if (logExpanded) {
            status.post(() -> {
                if (destroyed || status == null) return;
                status.scrollTo(0, status.getBottom());
            });
        }
    }

    private void updateLogSummary() {
        if (logSummary == null) return;
        String text = statusLog.toString().trim();
        String[] lines = text.split("\\R");
        String summary = lines.length == 0 || lines[lines.length - 1].trim().isEmpty()
                ? "Log vazio" : lines[lines.length - 1].trim();
        summary = summary.replaceAll("\\s+", " ");
        // CORRIGIDO: o resumo não trunca nem aplica ellipsize; o TextView
        // status abaixo permanece a fonte completa e rolável do diagnóstico.
        logSummary.setText(summary + (logExpanded ? " ▴" : " ▾"));
    }

    // Limpa somente o buffer visual; o diagnóstico interno da ponte/logcat
    // permanece intacto para investigação.
    private void clearLog() {
        if (destroyed || status == null) return;
        statusLog.setLength(0);
        status.setText("");
        logExpanded = false;
        status.setVisibility(View.GONE);
        appendStatusLine(null, "Log limpo.");
        updateLogSummary();
    }

    private void section(LinearLayout list, String text) {
        TextView heading = Ui.text(this, text, 10, Ui.BRIGHT, true);
        heading.setLetterSpacing(.13f);
        heading.setPadding(Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 5));
        list.addView(heading, lp(-1, Ui.dp(this, 29)));
    }

    private void addNotice(LinearLayout list, String title, String description) {
        addNotice(list, title, description, 0xFFB9AFC2);
    }

    private void addNotice(LinearLayout list, String title, String description, int titleColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        row.setBackground(Ui.rounded(0xFF102234, 9, this));
        TextView heading = Ui.text(this, title, 12, titleColor, true);
        TextView body = Ui.text(this, description, 10, Ui.MUTED, false);
        body.setLineSpacing(0, 1.05f);
        row.addView(heading, lp(-1, Ui.dp(this, 19)));
        row.addView(body, lp(-1, -2));
        LinearLayout.LayoutParams params = lp(-1, -2);
        params.bottomMargin = Ui.dp(this, 6);
        list.addView(row, params);
    }

    private void refreshLicenseInfo() { // CORRIGIDO BUG2: recalcula dias a cada atualização sem usar cache antigo.
        String key = activeLicenseKey == null ? "" : activeLicenseKey.trim(); // CORRIGIDO BUG2: normaliza a key ativa.
        if (key.isEmpty() && prefs != null) key = prefs.getString("key", ""); // CORRIGIDO BUG2: permite revalidar sessões que optaram por salvar a key.
        try { // CORRIGIDO BUG2: falha de cache nunca pode derrubar a overlay.
            licenseInfo = LicenseValidator.currentAccess(this, key); // CORRIGIDO BUG2: obtém dataAtual/dataExpiracao/dias em UTC.
            if (licenseStatus != null) { // CORRIGIDO BUG2: só toca a View se ela já foi criada.
                if (!licenseInfo.valid) licenseStatus.setText("Licença: key não validada"); // CORRIGIDO BUG2: estado ausente bloqueia recursos restritos.
                else if (licenseInfo.expired) licenseStatus.setText("Licença: expirada"); // CORRIGIDO BUG2: <=0 bloqueia tudo.
                else if (licenseInfo.daysRemaining == null) licenseStatus.setText("Licença: trial · configurações disponíveis · expiração não informada"); // CORRIGIDO: trial ativo não deixa a UI inteira desabilitada.
                else if (licenseInfo.fullAccess) licenseStatus.setText("Licença: " + licenseInfo.daysRemaining + " dias restantes · acesso total"); // CORRIGIDO: mantém o indicador informativo de acesso total.
                else licenseStatus.setText("Licença: " + licenseInfo.daysRemaining + " dias restantes · configurações disponíveis"); // CORRIGIDO: menos de 7 dias não bloqueia mais as configurações.
            } // CORRIGIDO BUG2: fecha a guarda da View.
            if (revalidateKeyButton != null) { // CORRIGIDO BUG2: botão só funciona quando há key ativa.
                boolean canRevalidate = !key.isEmpty(); // CORRIGIDO BUG2: evita revalidação de entrada vazia.
                revalidateKeyButton.setEnabled(canRevalidate); // CORRIGIDO BUG2: mantém a UI coerente.
                revalidateKeyButton.setAlpha(canRevalidate ? 1f : .52f); // CORRIGIDO BUG2: indica indisponibilidade sem crash.
            } // CORRIGIDO BUG2: fecha a guarda do botão.
        } catch (Throwable error) { // CORRIGIDO BUG2: cache corrompido vira bloqueio seguro e mensagem amigável.
            Log.e(FLOW_TAG, "OVERLAY_LICENSE_INFO_FAILED", error); // CORRIGIDO BUG2: registra a exceção sem key em texto.
            licenseInfo = LicenseKeyInfo.empty(System.currentTimeMillis()); // CORRIGIDO BUG2: falha de leitura não libera funções.
            if (licenseStatus != null) licenseStatus.setText("Licença: não foi possível calcular os dias"); // CORRIGIDO BUG2: informa o usuário.
        } // CORRIGIDO BUG2: encerra o tratamento do snapshot.
    } // CORRIGIDO BUG2: fecha atualização da licença.

    private void revalidateKey() { // CORRIGIDO BUG2: implementa o botão Revalidar key.
        if (destroyed || licenseRequest != null) return; // CORRIGIDO BUG2: impede duas validações concorrentes.
        String key = activeLicenseKey == null ? "" : activeLicenseKey.trim(); // CORRIGIDO BUG2: usa a key da sessão.
        if (key.isEmpty() && prefs != null) key = prefs.getString("key", "").trim(); // CORRIGIDO BUG2: fallback para key salva.
        if (key.isEmpty()) { message(false, "Nenhuma key ativa para revalidar."); return; } // CORRIGIDO BUG2: erro visível sem exceção.
        final String requestedKey = key; // CORRIGIDO BUG2: torna a key imutável para o callback assíncrono.
        if (revalidateKeyButton != null) { revalidateKeyButton.setEnabled(false); revalidateKeyButton.setText("Validando…"); } // CORRIGIDO BUG2: evita toques repetidos.
        message("Limpando cache e revalidando key…"); // CORRIGIDO BUG2: informa o início da operação.
        licenseRequest = LicenseValidator.revalidateKey(this, requestedKey, new LicenseValidator.Callback() { // CORRIGIDO BUG2: rede roda em Dispatchers.IO e callback retorna Main.
            @Override public void onResult(LicenseValidator.Result result) { // CORRIGIDO BUG2: recebe somente o resultado estruturado.
                if (destroyed) return; // CORRIGIDO BUG1: não toca Views depois que o serviço morreu.
                licenseRequest = null; // CORRIGIDO BUG2: libera nova tentativa.
                activeLicenseKey = requestedKey; // CORRIGIDO BUG2: mantém a key corrente para próximas revalidações.
                if (result.valid) { // CORRIGIDO BUG2: só salva sessão quando a API confirmou a key.
                    LicenseValidator.startMonitoring(getApplicationContext(), requestedKey); // CORRIGIDO BUG2: mantém revalidação periódica original.
                    refreshLicenseInfo(); // CORRIGIDO BUG2: mostra o novo número de dias.
                    updateCapabilityStates(); // CORRIGIDO BUG2: >=7 libera controles imediatamente.
                    message(true, result.message + (result.daysRemaining == null ? " · trial" : " · " + result.daysRemaining + " dias restantes")); // CORRIGIDO BUG2: confirma o resultado ao usuário.
                } else { // CORRIGIDO BUG2: falha não mantém cache antigo.
                    licenseInfo = LicenseKeyInfo.empty(System.currentTimeMillis()); // CORRIGIDO BUG2: bloqueia funções até nova validação válida.
                    refreshLicenseInfo(); // CORRIGIDO BUG2: atualiza texto e botão.
                    updateCapabilityStates(); // CORRIGIDO BUG2: aplica bloqueio seguro.
                    message(false, result.message); // CORRIGIDO BUG2: mostra erro amigável sem fechar o app.
                } // CORRIGIDO BUG2: fecha tratamento do resultado.
                if (revalidateKeyButton != null) revalidateKeyButton.setText("Revalidar key"); // CORRIGIDO BUG2: restaura o texto do botão.
            } // CORRIGIDO BUG2: fecha callback na Main Looper.
        }); // CORRIGIDO BUG2: inicia validação assíncrona protegida.
    } // CORRIGIDO BUG2: fecha revalidação.

    private TextView addRestrictedAction(LinearLayout list, String label, View.OnClickListener click) { // CORRIGIDO BUG2: registra ações restritas pela regra de 7 dias.
        TextView button = addAction(list, label, click); // CORRIGIDO BUG2: preserva a construção original do botão.
        licenseRestrictedButtons.add(button); // CORRIGIDO BUG2: permite aplicar enabled/alpha/texto centralmente.
        licenseRestrictedLabels.put(button, label); // CORRIGIDO BUG2: restaura o texto após revalidação.
        return button; // CORRIGIDO BUG2: mantém a API usada pelos campos do painel.
    } // CORRIGIDO BUG2: fecha fábrica de ações restritas.

    private void applyLicenseRestrictions() { // CORRIGIDO: ações não ficam todas cinza por causa de uma key com menos de 7 dias.
        boolean licenseAccess = hasActiveLicense(); // CORRIGIDO: somente key ausente ou expirada bloqueia ações.
        for (TextView button : licenseRestrictedButtons) {
            if (licenseAccess) {
                button.setEnabled(true); // CORRIGIDO: key ativa mantém o botão utilizável.
                button.setAlpha(1f);
                button.setText(licenseRestrictedLabels.get(button));
            } else {
                button.setEnabled(false); // CORRIGIDO: bloqueio continua correto sem licença ativa.
                button.setAlpha(.42f);
                button.setText("Ative uma key válida para usar"); // CORRIGIDO: mensagem identifica o bloqueio real.
            }
        }
    }

    private TextView addAction(LinearLayout list, String label, View.OnClickListener click) {
        TextView button = Ui.text(this, label, 10, accentForeground(panelAccentColor), true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        button.setTag("accent_button");
        button.setBackground(Ui.rounded(panelAccentColor, 9, this));
        button.setOnClickListener(click);
        LinearLayout.LayoutParams params = lp(-1, Ui.dp(this, 38));
        params.bottomMargin = Ui.dp(this, 5);
        list.addView(button, params);
        return button;
    }

    private int accentForeground(int color) {
        int red = (color >> 16) & 0xFF;
        int green = (color >> 8) & 0xFF;
        int blue = color & 0xFF;
        double luminance = (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0;
        return luminance > 0.55 ? Ui.INK : Ui.WHITE;
    }

    private Switch addToggle(LinearLayout list, String symbol, String title, String description, String key) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 5), Ui.dp(this, 6), Ui.dp(this, 5));
        row.setBackground(Ui.rounded(0xFF102234, 9, this));
        TextView icon = Ui.text(this, symbol, 20, Ui.BRIGHT, true);
        icon.setGravity(Gravity.CENTER);
        row.addView(icon, lp(Ui.dp(this, 34), Ui.dp(this, 43)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = Ui.text(this, title, 12, Ui.WHITE, true);
        TextView body = Ui.text(this, description, 9, Ui.MUTED, false);
        copy.addView(heading, lp(-1, Ui.dp(this, 18)));
        copy.addView(body, lp(-1, Ui.dp(this, 17)));
        row.addView(copy, lp(0, Ui.dp(this, 38), 1));
        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setScaleX(.78f);
        toggle.setScaleY(.78f);
        if (Build.VERSION.SDK_INT >= 21) {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            };
            toggle.setThumbTintList(new ColorStateList(states, new int[]{Ui.BRIGHT, Ui.MUTED}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{0x99F40DDA, 0x66526A83}));
        }
        updatingControls = true;
        toggle.setChecked(prefs.getBoolean("feature_" + key, false));
        updatingControls = false;
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!updatingControls) handleToggle(key, checked);
        });
        toggleViews.put(key, toggle);
        toggleDescriptions.put(key, body);
        toggleOriginalDescriptions.put(key, description);
        row.addView(toggle, lp(Ui.dp(this, 48), Ui.dp(this, 40)));
        LinearLayout.LayoutParams params = lp(-1, Ui.dp(this, 53));
        params.bottomMargin = Ui.dp(this, 5);
        list.addView(row, params);
        return toggle;
    }

    private void addSlider(LinearLayout list, String title, String key, int min, int max,
                           int initial, String hint) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 3));
        box.setBackground(Ui.rounded(0xFF102234, 9, this));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = Ui.text(this, title, 11, Ui.WHITE, true);
        line.addView(label, lp(0, Ui.dp(this, 20), 1));
        TextView value = Ui.text(this, "", 10, Ui.BRIGHT, true);
        value.setGravity(Gravity.CENTER);
        line.addView(value, lp(Ui.dp(this, 52), Ui.dp(this, 20)));
        box.addView(line, lp(-1, Ui.dp(this, 22)));
        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        int saved = Math.max(min, Math.min(max, prefs.getInt("slider_" + key, initial)));
        seek.setProgress(saved - min);
        value.setText(formatSliderValue(key, saved));
        box.addView(seek, lp(-1, Ui.dp(this, 28)));
        TextView helper = Ui.text(this, hint, 8, Ui.MUTED, false);
        box.addView(helper, lp(-1, Ui.dp(this, 14)));
        sliderViews.put(key, seek);
        sliderValues.put(key, value);
        sliderMins.put(key, min);
        sliderMaxes.put(key, max);
        sliderDefaults.put(key, initial);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(formatSliderValue(key, min + progress));
                if (fromUser && (key.equals("stretch_x") || key.equals("stretch_y"))) {
                    // Preview local durante o arraste; a preferência só muda após readback confirmado.
                    Log.d("DISPLAY_DEBUG", "STRETCH_PREVIEW key=" + key + " value=" + (min + progress));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                if (!updatingControls) {
                    pendingSliderPrevious.put("slider_" + key, min + bar.getProgress());
                }
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                if (updatingControls) return;
                int current = min + bar.getProgress();
                String pendingKey = "slider_" + key;
                if (!pendingToggleOperations.isEmpty()) {
                    message("Aplicando outra função… aguarde o resultado antes de tocar novamente");
                    return;
                }
                int previous = pendingSliderPrevious.containsKey(pendingKey)
                        ? pendingSliderPrevious.get(pendingKey)
                        : (prefs.contains(pendingKey)
                        ? prefs.getInt(pendingKey, initial) : initial);
                if (key.equals("pointer_speed")) {
                    message("Aplicando " + title + "…");
                    applyPointerSpeedLocal(current, previous, min, title);
                    return;
                } else if (key.equals("long_press") || key.equals("animation_scale")) {
                    message("Aplicando " + title + "…");
                    applySafeSlider(key, current, previous, min, title);
                    return;
                } else if (key.equals("stretch_x") || key.equals("stretch_y")) {
                    pendingSliderPrevious.remove(pendingKey);
                    if (selectedFlagshipPreset() == null) {
                        scheduleLiveStretchApply();
                        message(title + " selecionado; último valor será aplicado após 300 ms");
                    } else {
                        message("Preset flagship selecionado; use APLICAR resolução / DPI");
                    }
                    return;
                }
                prefs.edit().putInt(pendingKey, current).apply();
                message(title + " salvo");
            }
        });
        LinearLayout.LayoutParams params = lp(-1, Ui.dp(this, 67));
        params.bottomMargin = Ui.dp(this, 5);
        list.addView(box, params);
    }

    private String formatSliderValue(String key, int value) {
        if (key.equals("animation_scale")) return String.format(Locale.US, "%.1fx", value / 100f);
        return key.equals("pointer_speed") ? (value > 0 ? "+" + value : String.valueOf(value))
                : String.valueOf(value);
    }

    private static final String POINTER_SPEED_SETTING = "pointer_speed";
    private static final String POINTER_SPEED_ORIGINAL_LOCAL = "original_pointer_speed_local";

    private Integer readPointerSpeedLocal() {
        try {
            // Android usa 0 como valor padrão quando o setting ainda não foi
            // materializado; não tratar essa situação como “não aparece”.
            int value = Settings.System.getInt(getContentResolver(), POINTER_SPEED_SETTING, 0);
            Log.d(FLOW_TAG, "POINTER_READ path=local value=" + value);
            return value;
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "POINTER_READ_FAILED path=local cause="
                    + error.getClass().getSimpleName(), error);
            return null;
        }
    }

    private int pointerUiValue(int systemValue) {
        return Math.max(-7, Math.min(7, systemValue));
    }

    private int pointerSystemValue(int uiValue) {
        return Math.max(-7, Math.min(7, uiValue));
    }

    private void refreshPointerSliderFromSystem() {
        SeekBar seek = sliderViews.get("pointer_speed");
        TextView value = sliderValues.get("pointer_speed");
        Integer current = readPointerSpeedLocal();
        if (seek == null || value == null || current == null) return;
        int uiValue = pointerUiValue(current);
        updatingControls = true;
        seek.setProgress(uiValue - sliderMins.get("pointer_speed"));
        updatingControls = false;
        value.setText(formatSliderValue("pointer_speed", uiValue));
    }

    private void restorePointerSliderVisual(int previousValue, int min) {
        SeekBar seek = sliderViews.get("pointer_speed");
        if (seek == null) return;
        updatingControls = true;
        seek.setProgress(previousValue - min);
        updatingControls = false;
    }

    /** Settings.System é lido na Main, mas a escrita/releitura/rollback ocorre
     * fora da UI; toda View é atualizada somente no callback Main. */
    private void applyPointerSpeedLocal(int requestedValue, int previousValue,
                                        int min, String title) {
        final String pendingKey = "slider_pointer_speed";
        pendingToggleOperations.add(pendingKey);
        final Integer previousSystem = readPointerSpeedLocal();
        final int requestedSystem = pointerSystemValue(requestedValue);
        Log.d(FLOW_TAG, "POINTER_APPLY_START path=local previous="
                + (previousSystem == null ? "unavailable" : previousSystem)
                + " requestedUi=" + requestedValue + " requestedSystem=" + requestedSystem);
        if (previousSystem == null) {
            pendingToggleOperations.remove(pendingKey);
            pendingSliderPrevious.remove(pendingKey);
            restorePointerSliderVisual(previousValue, min);
            updateCapabilityStates();
            postMessage(false, title + " falhou: leitura local de Settings.System.pointer_speed não disponível");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !hasDirectSettingsPermission()) {
            // CORRIGIDO: sem WRITE_SETTINGS/WRITE_SECURE_SETTINGS, tenta o fallback Shizuku real;
            // se ele falhar, o Android abre a autorização especial.
            applyPointerSpeedViaShizuku(requestedValue, previousValue, min,
                    previousSystem, requestedSystem, title);
            return;
        }

        ShizukuManager.launchIo("santos-pointer-speed", () -> {
            boolean putOk = false;
            String failure = "Settings.System.putInt retornou false";
            Integer confirmed = null;
            boolean rollbackOk = true;
            try {
                putOk = Settings.System.putInt(getContentResolver(),
                        POINTER_SPEED_SETTING, requestedSystem);
                confirmed = putOk ? readPointerSpeedLocal() : null;
                boolean confirmedOk = putOk && confirmed != null && confirmed == requestedSystem;
                if (!confirmedOk) {
                    Integer current = readPointerSpeedLocal();
                    if (current != null && !current.equals(previousSystem)) {
                        rollbackOk = Settings.System.putInt(getContentResolver(),
                                POINTER_SPEED_SETTING, previousSystem);
                        Integer rollbackConfirmed = rollbackOk ? readPointerSpeedLocal() : null;
                        rollbackOk = rollbackOk && rollbackConfirmed != null
                                && rollbackConfirmed.equals(previousSystem);
                    }
                }
                final boolean resultOk = confirmedOk;
                final Integer resultConfirmed = confirmed;
                final String resultFailure = failure;
                final boolean resultRollback = rollbackOk;
                mainHandler.post(() -> finishPointerSpeedApply(resultOk, resultConfirmed,
                        resultFailure, resultRollback, requestedValue, previousValue, min,
                        previousSystem, requestedSystem, title));
            } catch (Throwable error) {
                final String detail = error.getClass().getSimpleName() + ": "
                        + (error.getMessage() == null ? "falha desconhecida" : error.getMessage());
                final Integer failedConfirmed = confirmed;
                Log.e(FLOW_TAG, "POINTER_APPLY_EXCEPTION path=local", error);
                mainHandler.post(() -> finishPointerSpeedApply(false, failedConfirmed, detail,
                        false, requestedValue, previousValue, min, previousSystem,
                        requestedSystem, title));
            }
        });
    }

    private void applyPointerSpeedViaShizuku(int requestedValue, int previousValue,
                                              int min, int previousSystem,
                                              int requestedSystem, String title) {
        ShizukuManager.launchIo("santos-pointer-speed-shizuku", () -> {
            ShizukuBridge.CommandResult write = ShizukuBridge.executeArgvForDisplay(
                    new String[]{"settings", "put", "system", POINTER_SPEED_SETTING,
                            String.valueOf(requestedSystem)});
            ShizukuBridge.CommandResult read = write.ok
                    ? ShizukuBridge.executeArgvForDisplay(
                    new String[]{"settings", "get", "system", POINTER_SPEED_SETTING})
                    : write;
            String actual = read.stdout.trim();
            boolean confirmedOk = write.ok && read.ok
                    && actual.matches("-?\\d+")
                    && Integer.parseInt(actual) == requestedSystem;
            boolean rollbackOk = true;
            if (!confirmedOk && write.ok) {
                ShizukuBridge.CommandResult rollback = ShizukuBridge.executeArgvForDisplay(
                        new String[]{"settings", "put", "system", POINTER_SPEED_SETTING,
                                String.valueOf(previousSystem)});
                ShizukuBridge.CommandResult rollbackRead = rollback.ok
                        ? ShizukuBridge.executeArgvForDisplay(
                        new String[]{"settings", "get", "system", POINTER_SPEED_SETTING})
                        : rollback;
                rollbackOk = rollback.ok && rollbackRead.ok
                        && rollbackRead.stdout.trim().equals(String.valueOf(previousSystem));
            }
            final boolean resultOk = confirmedOk;
            final Integer resultValue = actual.matches("-?\\d+")
                    ? Integer.parseInt(actual) : null;
            final String detail = "Shizuku settings put exit=" + write.exitCode
                    + " stdout=" + write.stdout + " stderr=" + write.stderr
                    + " · readback exit=" + read.exitCode + " stdout=" + read.stdout
                    + " stderr=" + read.stderr;
            final boolean resultRollback = rollbackOk;
            mainHandler.post(() -> {
                if (!resultOk) requestWriteSettingsPermission();
                finishPointerSpeedApply(resultOk, resultValue, detail, resultRollback,
                        requestedValue, previousValue, min, previousSystem,
                        requestedSystem, title);
            });
        });
    }

    private void finishPointerSpeedApply(boolean ok, Integer confirmed, String failure,
                                         boolean rollbackOk, int requestedValue,
                                         int previousValue, int min, int previousSystem,
                                         int requestedSystem, String title) {
        if (destroyed) return;
        final String pendingKey = "slider_pointer_speed";
        if (ok) {
            SharedPreferences.Editor editor = prefs.edit().putInt("slider_pointer_speed", requestedValue);
            if (!prefs.contains(POINTER_SPEED_ORIGINAL_LOCAL)) {
                editor.putInt(POINTER_SPEED_ORIGINAL_LOCAL, previousSystem);
            }
            editor.apply();
        } else {
            restorePointerSliderVisual(previousValue, min);
        }
        pendingToggleOperations.remove(pendingKey);
        pendingSliderPrevious.remove(pendingKey);
        updateCapabilityStates();
        String confirmation = confirmed == null ? "unavailable" : String.valueOf(confirmed);
        postMessage(ok, ok
                ? title + " aplicado localmente e confirmado: anterior=" + previousSystem
                        + " solicitado=" + requestedSystem + " confirmado=" + confirmation
                : title + " falhou: " + failure + "; anterior=" + previousSystem
                        + "; confirmado=" + confirmation + "; rollback=" + rollbackOk);
    }

    private void restorePointerSpeedLocal() {
        if (!prefs.contains(POINTER_SPEED_ORIGINAL_LOCAL)) {
            prefs.edit().remove("slider_pointer_speed").apply();
            refreshPointerSliderFromSystem();
            return;
        }
        int original = prefs.getInt(POINTER_SPEED_ORIGINAL_LOCAL, 0);
        Integer current = readPointerSpeedLocal();
        if (current == null) {
            postMessage(false, "Velocidade do ponteiro não restaurada: leitura local indisponível");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !hasDirectSettingsPermission()) {
            // CORRIGIDO: a restauração também oferece o fluxo de autorização
            // especial, em vez de deixar o estado alterado sem orientação.
            requestWriteSettingsPermission();
            postMessage(false, "Velocidade do ponteiro não restaurada: conceda WRITE_SETTINGS e tente novamente");
            return;
        }
        try {
            boolean putOk = Settings.System.putInt(getContentResolver(),
                    POINTER_SPEED_SETTING, original);
            Integer confirmed = putOk ? readPointerSpeedLocal() : null;
            boolean ok = putOk && confirmed != null && confirmed == original;
            Log.d(FLOW_TAG, "POINTER_RESTORE path=local previous=" + current
                    + " requested=" + original + " confirmed="
                    + (confirmed == null ? "unavailable" : confirmed) + " ok=" + ok);
            if (ok) {
                prefs.edit().remove(POINTER_SPEED_ORIGINAL_LOCAL)
                        .remove("slider_pointer_speed").apply();
                refreshPointerSliderFromSystem();
                postMessage(true, "Velocidade do ponteiro original restaurada e confirmada");
            } else {
                postMessage(false, "Velocidade do ponteiro não restaurada: putInt/confirm="
                        + (confirmed == null ? "unavailable" : confirmed));
            }
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "POINTER_RESTORE_EXCEPTION path=local", error);
            postMessage(false, "Velocidade do ponteiro não restaurada: "
                    + error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "sem mensagem" : error.getMessage()));
        }
    }

    private boolean hasDirectSettingsPermission() {
        return Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)
                || hasSecureSettingsPermission();
    }

    // CORRIGIDO: WRITE_SETTINGS não é runtime permission comum; o Android exige
    // a tela ACTION_MANAGE_WRITE_SETTINGS para o usuário conceder o acesso.
    private void requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "WRITE_SETTINGS_SETTINGS_OPEN_FAILED", error);
            message(false, "Abra manualmente Acesso especial > Modificar configurações do sistema para o PAINEL SANTOS");
        }
    }

    private void addRendererControls(LinearLayout list) {
        section(list, "RENDERER / DISPLAY");
        addNotice(list, "Renderer / Vulkan",
                "Hardware, suporte Android, renderer real do painel e renderer externo são estados diferentes; a API pública não confirma troca de renderer por package.",
                Ui.MUTED);
        rendererStatus = Ui.text(this, "Atual: leitura pendente · externo: não exposto", 9, Ui.BRIGHT, false);
        rendererStatus.setLineSpacing(0, 1.05f);
        list.addView(rendererStatus, lp(-1, Ui.dp(this, 42)));
        rendererSelector = spinner(this, new String[]{
                "Automático (Android)", "OpenGL/GL", "Vulkan", "SkiaGL", "SkiaVulkan", "ANGLE", "Outro"
        });
        int savedRenderer = Math.max(0, Math.min(6,
                prefs.getInt("renderer_requested_position", 0)));
        rendererSelector.setSelection(savedRenderer);
        addSpinnerRow(list, "Renderer solicitado", rendererSelector);
        rendererApply = addAction(list, "LER renderer real (sem escrita)",
                v -> applyRendererFromUi());
        rendererApply.setEnabled(true);
        rendererApply.setAlpha(1f);
    }

    private void refreshRendererInfo() {
        if (destroyed || rendererStatus == null) return;
        rendererStatus.setText(rendererReadbackText());
        Log.d("RENDERER_DEBUG", "READBACK source=PackageManager/ActivityManager panel=not_exposed external=unsupported");
    }

    private void applyRendererFromUi() {
        if (destroyed) return;
        String requested = rendererSelector == null ? "não selecionado"
                : String.valueOf(rendererSelector.getSelectedItem());
        String result = "Solicitado=" + requested + "\n" + rendererReadbackText()
                + "\nResultado: não suportado pelo Android; nenhuma escrita foi feita";
        if (rendererStatus != null) rendererStatus.setText(result);
        Log.d("RENDERER_DEBUG", "APPLY requested=" + requested + " confirmed=false");
        postMessage(false, "Renderer não aplicado: API Android não confirma troca no painel ou em app externo");
    }

    private String rendererReadbackText() {
        return "Vulkan hardware: " + (vulkanHardwareAvailable() ? "disponível" : "não exposto")
                + "\nVulkan suportado pelo Android: " + (vulkanAndroidSupported() ? "sim" : "não confirmado")
                + "\nOpenGL suportado pelo Android: " + (openGlAvailable() ? "sim" : "não confirmado")
                + "\nRenderer real do próprio painel: não exposto por API pública"
                + "\nRenderer aplicado no próprio painel: não confirmado"
                + "\nRenderer de app/jogo externo: não exposto"
                + "\nRenderer externo: não suportado pelo Android";
    }

    private boolean hasSecureSettingsPermission() {
        return checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBreventInstalled() {
        try {
            getPackageManager().getApplicationInfo("me.piebridge.brevent", 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (RuntimeException error) {
            Log.w("BREVENT_DEBUG", "Falha lendo instalação do Brevent", error);
            return false;
        }
    }

    private void tryActivateBrevent() {
        if (destroyed) return;
        if (hasSecureSettingsPermission()) {
            setFeatureState("brevent", true);
            setToggleChecked("brevent", true);
            postMessage(true, "Permissão concedida — funciona permanentemente, sem internet");
            updateCapabilityStates();
            return;
        }
        if (!isBreventInstalled()) {
            postMessage(false, "Brevent não instalado. Instale-o e ative o servidor via Wireless Debugging.");
            return;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage("me.piebridge.brevent");
        if (launch == null) {
            postMessage(false, "Brevent instalado, mas não expõe uma Activity pública para abrir; estado não confirmado.");
            return;
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            setToggleChecked("brevent", false);
            postMessage(null, "Brevent instalado e aberto; o estado de execução/concessão não é exposto por API pública confirmável. ADB: pm grant painel.sensi.santos android.permission.WRITE_SECURE_SETTINGS");
        } catch (RuntimeException error) {
            Log.e("BREVENT_DEBUG", "Falha abrindo Brevent", error);
            postMessage(false, "Brevent não pôde ser aberto: " + error.getClass().getSimpleName());
        }
    }

    private void addGamePackageControl(LinearLayout list) {
        addNotice(list, "Package do jogo (opcional)",
                "Informe o package exato para Game Mode, Tela cheia e políticas externas; o painel nunca adivinha um jogo.", Ui.MUTED);
        gamePackageInput = new android.widget.EditText(this);
        gamePackageInput.setSingleLine(true);
        gamePackageInput.setFocusable(true);
        gamePackageInput.setFocusableInTouchMode(true);
        gamePackageInput.setCursorVisible(true);
        gamePackageInput.setSelectAllOnFocus(false);
        gamePackageInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        gamePackageInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        gamePackageInput.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        gamePackageInput.setText(prefs.getString("selected_game_package", ""));
        gamePackageInput.setHint("ex.: com.exemplo.jogo");
        gamePackageInput.setHintTextColor(Ui.MUTED);
        gamePackageInput.setTextColor(Ui.WHITE);
        gamePackageInput.setTextSize(10);
        gamePackageInput.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        gamePackageInput.setBackground(Ui.outline(0xFF102234, 0xFF465250, 8, this));
        gamePackageInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                gamePackageInput.clearFocus();
                return true;
            }
            return false;
        });
        gamePackageInput.setOnFocusChangeListener((v, focused) -> {
            if (focused || destroyed) return;
            String value = gamePackageInput.getText().toString().trim();
            if (isExternalPackage(value)) {
                prefs.edit().putString("selected_game_package", value).apply();
                postMessage(true, "Package do jogo salvo: " + value);
            } else if (value.isEmpty()) {
                prefs.edit().remove("selected_game_package").apply();
                postMessage(null, "Nenhum package de jogo selecionado");
            } else {
                gamePackageInput.setText(prefs.getString("selected_game_package", ""));
                postMessage(false, "Package inválido ou igual ao próprio painel; nenhuma política externa será aplicada");
            }
        });
        list.addView(gamePackageInput, lp(-1, Ui.dp(this, 42)));
    }

    private boolean isExternalPackage(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
                && !getPackageName().equals(value);
    }

    private String selectedGamePackage() {
        String value = gamePackageInput == null
                ? prefs.getString("selected_game_package", "")
                : gamePackageInput.getText().toString().trim();
        return isExternalPackage(value) ? value : "";
    }

    private void addShizukuCard(LinearLayout list) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 9), Ui.dp(this, 6), Ui.dp(this, 6), Ui.dp(this, 6));
        row.setBackground(Ui.rounded(0xFF102234, 9, this));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView heading = Ui.text(this, "Shizuku", 12, Ui.WHITE, true);
        // CORRIGIDO: não carregar/consultar a ponte durante a montagem inicial;
        // Shizuku só é tocado após o botão Autorizar/Testar.
        shizukuStatus = Ui.text(this, "Toque em AUTORIZAR para verificar", 9, Ui.MUTED, false);
        copy.addView(heading, lp(-1, Ui.dp(this, 18)));
        copy.addView(shizukuStatus, lp(-1, Ui.dp(this, 16)));
        row.addView(copy, lp(0, Ui.dp(this, 35), 1));
        TextView auth = Ui.text(this, "Autorizar", 10, accentForeground(panelAccentColor), true);
        auth.setGravity(Gravity.CENTER);
        auth.setTag("accent_button");
        auth.setBackground(Ui.rounded(panelAccentColor, 8, this));
        auth.setOnClickListener(v -> requestShizuku());
        row.addView(auth, lp(Ui.dp(this, 70), Ui.dp(this, 30)));
        list.addView(row, lp(-1, Ui.dp(this, 50)));
        addAction(list, "TESTAR PONTE SHIZUKU", v -> runBridgeTest());
    }

    private void runBridgeTest() {
        Log.d(FLOW_TAG, "OVERLAY_BRIDGE_TEST_CLICK");
        if (destroyed || bridgeTestInProgress) return;
        registerShizukuListenersIfNeeded();
        bridgeTestInProgress = true;
        bridgeVerified = false;
        // Não consultar binder/status na Main Thread neste clique.
        setBridgeTestStatus(null, "Testando echo SANTOS_SHIZUKU_OK…");
        ShizukuBridge.testBridge(this, (ok, msg) -> {
            if (destroyed) return;
            Log.d(FLOW_TAG, "OVERLAY_BRIDGE_TEST_RESULT ok=" + ok + " detail=" + msg);
            bridgeTestInProgress = false;
            bridgeVerified = ok;
            // O callback já chega na Main Looper; usa o resultado observado e
            // não repete hasPermission() no caminho visual do botão.
            updateCapabilityStates(ok);
            setBridgeTestStatus(ok, msg);
            if (ok) runSupportVerification();
        });
    }

    // CORRIGIDO: a verificação completa usa o botão original TESTAR PONTE
    // SHIZUKU; nenhum botão extra foi adicionado à interface.
    private void runSupportVerification() {
        if (destroyed) return;
        message(null, "Iniciando verificação real de suporte por função…");
        ShellManager.probeCapabilities(this, new ShellManager.CapabilityCallback() {
            @Override public void onFinished(Set<String> unsupported, Map<String, String> details) {
                if (destroyed) return;
                unavailableShellFeatures.clear();
                unavailableShellFeatures.addAll(unsupported);
                shellCapabilitiesKnown = true;
                for (Map.Entry<String, String> entry : details.entrySet()) {
                    boolean supported = !unsupported.contains(entry.getKey());
                    message(supported, "SUPORTE " + entry.getKey() + ": " + entry.getValue());
                }
                updateCapabilityStates(true);
            }
        });
    }

    private void setBridgeTestStatus(Boolean ok, String detail) {
        if (destroyed || status == null) return;
        String state = ok == null ? "testando" : (ok ? "ponte validada" : "falha na ponte");
        String safe = detail == null ? "" : detail.trim();
        appendStatusLine(ok, "Shizuku: " + state + "\n" + safe);
    }

    // CORRIGIDO: a ponte fica fora do caminho de startup e só passa a observar
    // o binder depois de uma ação explícita dentro do painel.
    private void registerShizukuListenersIfNeeded() {
        if (destroyed || (binderReceivedListenerRegistered && binderDeadListenerRegistered
                && permissionResultListenerRegistered)) return;
        try {
            if (!binderReceivedListenerRegistered) {
                Shizuku.addBinderReceivedListener(binderReceivedListener);
                binderReceivedListenerRegistered = true;
            }
            if (!binderDeadListenerRegistered) {
                Shizuku.addBinderDeadListener(binderDeadListener);
                binderDeadListenerRegistered = true;
            }
            if (!permissionResultListenerRegistered) {
                Shizuku.addRequestPermissionResultListener(permissionResultListener);
                permissionResultListenerRegistered = true;
            }
            Log.d(FLOW_TAG, "OVERLAY_SHIZUKU_LISTENERS_REGISTERED");
        } catch (Throwable error) {
            Log.e(FLOW_TAG, "OVERLAY_SHIZUKU_LISTENER_REGISTER_FAILED", error);
            // Remover qualquer listener parcialmente registrado evita callback
            // tardio se o firmware/API rejeitar uma das inscrições.
            try { if (binderReceivedListenerRegistered) Shizuku.removeBinderReceivedListener(binderReceivedListener); } catch (Throwable ignored) { }
            try { if (binderDeadListenerRegistered) Shizuku.removeBinderDeadListener(binderDeadListener); } catch (Throwable ignored) { }
            try { if (permissionResultListenerRegistered) Shizuku.removeRequestPermissionResultListener(permissionResultListener); } catch (Throwable ignored) { }
            binderReceivedListenerRegistered = false;
            binderDeadListenerRegistered = false;
            permissionResultListenerRegistered = false;
            message(false, "Shizuku: API indisponível; painel continua aberto");
        }
    }

    private void requestShizuku() {
        Log.d(FLOW_TAG, "OVERLAY_SHIZUKU_AUTHORIZE_CLICK");
        if (destroyed) return;
        registerShizukuListenersIfNeeded();
        // Primeiro consulta o binder em background; o snapshot inicial não pode
        // ser interpretado como "Shizuku indisponível".
        message(null, "Shizuku: verificando binder…");
        ShizukuBridge.refreshStatusAsync(this, (available, authorized, current) -> {
            if (destroyed) return;
            if (authorized) {
                refreshShizuku();
                message("Shizuku já autorizado");
                return;
            }
            if (available) {
                ShizukuBridge.requestPermission();
                message("Confirme a autorização no Shizuku");
            } else {
                message("Shizuku não respondeu: inicie o Shizuku e tente novamente");
                try {
                    Intent intent = getPackageManager().getLaunchIntentForPackage(
                            "moe.shizuku.privileged.api");
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                } catch (RuntimeException error) {
                    Log.e("OverlayService", "Não foi possível abrir o Shizuku Manager", error);
                    message(false, "Não foi possível abrir o Shizuku Manager: "
                            + error.getClass().getSimpleName());
                }
            }
            if (panel != null) panel.postDelayed(this::refreshShizuku, 1000);
        });
    }

    private void refreshShizuku() {
        Log.d(FLOW_TAG, "OVERLAY_SHIZUKU_REFRESH");
        if (destroyed || shizukuStatusProbeInProgress) return;
        shizukuStatusProbeInProgress = true;
        ShizukuBridge.refreshStatusAsync(this, (available, authorized, current) -> {
            if (destroyed) return;
            shizukuStatusProbeInProgress = false;
            if (shizukuStatus != null) shizukuStatus.setText(current
                    + (bridgeVerified ? " · ponte testada" : " · ponte não testada"));
            if (!authorized) {
                bridgeVerified = false;
                shellCapabilitiesKnown = false;
                shellCapabilityProbeInProgress = false;
                unavailableShellFeatures.clear();
            }
            updateCapabilityStates(authorized);
            if (panel != null && panel.isAttachedToWindow()) {
                panel.postDelayed(this::refreshShizuku, 1800);
            }
        });
    }

    private boolean hasActiveLicense() { // CORRIGIDO: separa sessão válida do indicador informativo de dias restantes.
        return licenseInfo != null && licenseInfo.valid && !licenseInfo.expired; // CORRIGIDO: key válida/não expirada não deixa toda a UI artificialmente cinza.
    } // CORRIGIDO: o bloqueio continua apenas para key ausente ou expirada.

    private void updateCapabilityStates() {
        updateCapabilityStates(ShizukuBridge.hasPermission());
    }

    private void updateCapabilityStates(boolean authorized) {
        if (destroyed) return;
        refreshLicenseInfo(); // CORRIGIDO BUG2: recalcula dias antes de bloquear/liberar qualquer função.
        boolean licenseAccess = hasActiveLicense(); // CORRIGIDO: qualquer key válida/não expirada deixa os controles utilizáveis.
        boolean licenseExpired = licenseInfo != null && licenseInfo.expired; // CORRIGIDO: key expirada continua bloqueada.
        boolean bridgeReady = authorized; // Shizuku autorizado basta; teste manual é diagnóstico.
        boolean globalOperationBusy = !pendingToggleOperations.isEmpty();
        boolean securePermission = hasSecureSettingsPermission();
        Switch breventToggle = toggleViews.get("brevent");
        if (breventToggle != null) {
            setToggleChecked("brevent", securePermission);
            if (securePermission) prefs.edit().putBoolean("feature_brevent", true).apply();
            else prefs.edit().remove("feature_brevent").apply();
        }
        // CORRIGIDO: não sondar CPU/GPU/touch automaticamente após autorizar.
        // Cada recurso é validado apenas quando o usuário o ativa, evitando uma
        // rajada de processos remotos que derruba o binder em alguns firmwares.
        for (Map.Entry<String, Switch> entry : toggleViews.entrySet()) {
            String key = entry.getKey();
            // Cada switch declara sua dependência: somente ocultar na captura é
            // local; os demais ajustes de sistema/driver usam a ponte remota.
            boolean localControl = "hide_for_capture".equals(key) || "brevent".equals(key);
            boolean thermal = "thermal".equals(key);
            boolean unavailable = unavailableShellFeatures.contains(key);
            boolean busy = !localControl && globalOperationBusy;
            boolean licenseRestricted = !localControl && !thermal; // CORRIGIDO BUG2: todos os ajustes remotos usam a regra de 7 dias.
            boolean capabilityAvailable = localControl || licenseAccess; // CORRIGIDO: o controle fica clicável; a operação ainda valida Shizuku.
            // CORRIGIDO: uma sonda negativa não é prova permanente de incompatibilidade;
            // manter o switch habilitado permite nova tentativa quando o aparelho der suporte.
            entry.getValue().setEnabled(capabilityAvailable && !thermal && !busy);
            entry.getValue().setAlpha((thermal || (licenseRestricted && !licenseAccess)) ? .42f : 1f);
            TextView description = toggleDescriptions.get(key);
            if (description != null) {
                String original = toggleOriginalDescriptions.getOrDefault(key, "");
                if ("brevent".equals(key) && securePermission) {
                    original = "Permissão concedida — funciona permanentemente, sem internet";
                }
                description.setText(licenseRestricted && !licenseAccess
                        ? "Ative uma key válida para usar esta configuração"
                        : (unavailable
                        ? "Último teste falhou; tente novamente para confirmar o suporte"
                        : original));
            }
            if (thermal && entry.getValue().isChecked()) {
                updatingControls = true;
                entry.getValue().setChecked(false);
                updatingControls = false;
                prefs.edit().remove("feature_thermal").apply();
            }
        }

        // pointer_speed é local/API Android e não herda a autorização Shizuku.
        SeekBar pointer = sliderViews.get("pointer_speed");
        if (pointer != null) {
            boolean busy = globalOperationBusy;
            pointer.setEnabled(!busy);
            pointer.setAlpha(busy ? .52f : 1f);
        }
        SeekBar longPress = sliderViews.get("long_press");
        if (longPress != null) {
            boolean canUse = licenseAccess && !globalOperationBusy; // Uma fila global evita callbacks concorrentes.
            longPress.setEnabled(canUse);
            longPress.setAlpha(canUse ? 1f : .52f);
        }
        SeekBar animation = sliderViews.get("animation_scale");
        if (animation != null) {
            boolean canUse = licenseAccess && !globalOperationBusy; // Uma fila global evita callbacks concorrentes.
            animation.setEnabled(canUse);
            animation.setAlpha(canUse ? 1f : .52f);
        }

        // CORRIGIDO: leitura de wm/density não acontece automaticamente após
        // autorização. O botão "LER wm size / wm density AGORA" e o worker de
        // aplicação fazem a consulta somente quando necessário.
        if (resolutionSave != null) {
            resolutionSave.setEnabled(licenseAccess && !globalOperationBusy);
            resolutionSave.setAlpha(licenseAccess ? 1f : .52f); // CORRIGIDO: só key ausente/expirada reduz a ação.
        }
        boolean canRestore = DisplayManagerHelper.hasSavedSnapshot(this)
                || (prefs.contains("original_display_size")
                && prefs.contains("original_display_density")); // Snapshot CAT é a fonte principal.
        if (resolutionRestore != null) {
            resolutionRestore.setEnabled(canRestore && licenseAccess && !globalOperationBusy);
            resolutionRestore.setAlpha(canRestore && licenseAccess ? 1f : .52f); // CORRIGIDO: só key ausente/expirada reduz a ação.
        }
        if (displayApply != null) {
            displayApply.setEnabled(licenseAccess && !globalOperationBusy);
            displayApply.setAlpha(licenseAccess ? 1f : .52f); // CORRIGIDO: não bloquear a UI antes da tentativa.
        }
        if (displayReset != null) {
            displayReset.setEnabled(canRestore && licenseAccess && !globalOperationBusy);
            displayReset.setAlpha(canRestore && licenseAccess ? 1f : .52f); // CORRIGIDO: só key ausente/expirada reduz a ação.
        }
        if (resolutionSelector != null) resolutionSelector.setEnabled(licenseAccess && !globalOperationBusy);
        if (dpiSelector != null) dpiSelector.setEnabled(licenseAccess && !globalOperationBusy);
        SeekBar stretchX = sliderViews.get("stretch_x");
        SeekBar stretchY = sliderViews.get("stretch_y");
        boolean canStretch = licenseAccess && !globalOperationBusy;
        if (stretchX != null) {
            stretchX.setEnabled(canStretch);
            stretchX.setAlpha(canStretch ? 1f : .52f);
        }
        if (stretchY != null) {
            stretchY.setEnabled(canStretch);
            stretchY.setAlpha(canStretch ? 1f : .52f);
        }
        for (Map.Entry<Integer, TextView> entry : refreshChoices.entrySet()) {
            boolean supported = refreshSupport.getOrDefault(entry.getKey(), false);
            entry.getValue().setEnabled(supported && !globalOperationBusy);
        }
        if (refreshApply != null) {
            boolean canApplyRefresh = licenseAccess && selectedRefreshHz >= 0
                    && !globalOperationBusy;
            refreshApply.setEnabled(canApplyRefresh);
            refreshApply.setAlpha(canApplyRefresh ? 1f : .52f); // CORRIGIDO BUG2: reflete o bloqueio por licença.
        }
        if (refreshRestore != null) {
            refreshRestore.setEnabled(licenseAccess && !globalOperationBusy);
            refreshRestore.setAlpha(licenseAccess ? 1f : .52f); // CORRIGIDO: só key ausente/expirada reduz a ação.
        }
        if (rendererApply != null) {
            boolean canApplyRenderer = licenseAccess && !globalOperationBusy;
            rendererApply.setEnabled(canApplyRenderer);
            rendererApply.setAlpha(canApplyRenderer ? 1f : .52f);
        }
        applyLicenseRestrictions();
    }

    private boolean beginRemoteOperation(String key) {
        if (destroyed) return false;
        if (!pendingToggleOperations.isEmpty()) {
            message(null, "Outra função está sendo aplicada; aguarde o resultado no LOG");
            return false;
        }
        pendingToggleOperations.add(key);
        updateCapabilityStates();
        return true;
    }

    private void finishRemoteOperation(String key) {
        pendingToggleOperations.remove(key);
        if (!destroyed) updateCapabilityStates();
    }

    private void runCacheAction() {
        if (!beginRemoteOperation("cache_action")) return;
        ShellManager.clearCache((ok, msg) -> {
            if (destroyed) return;
            if (ok) prefs.edit().putBoolean("feature_cache", true).apply();
            finishRemoteOperation("cache_action");
            postMessage(ok, msg);
        });
    }

    private void runRamAction() {
        if (!beginRemoteOperation("ram_action")) return;
        ShellManager.clearRam((ok, msg) -> {
            if (destroyed) return;
            if (ok) prefs.edit().putBoolean("feature_ram", true).apply();
            finishRemoteOperation("ram_action");
            postMessage(ok, msg);
        });
    }

    private void selectTab(int page) {
        selectedTab = page;
        String[] titles = {"TOQUE", "TELA", "OTIMIZAÇÃO", "LOGS", "CONFIGURAÇÕES"};
        int selectedPage = Math.max(0, Math.min(4, page));
        if (activeTabTitle != null) activeTabTitle.setText(titles[selectedPage]);
        for (int i = 0; i < 5; i++) {
            boolean selected = i == selectedPage;
            tabViews[i].setBackground(Ui.rounded(selected ? panelAccentColor : 0xFF0D2338, 14, this));
        }
        for (int i = 0; i < 5; i++) {
            pages.getChildAt(i).setVisibility(i == selectedPage ? View.VISIBLE : View.GONE);
        }
        if (selectedPage == 1) {
            refreshDisplayModes();
            refreshRendererInfo();
        }
        if (selectedPage == 4) refreshDeviceInfo();
    }

    private void handleToggle(String key, boolean checked) {
        Log.d(FLOW_TAG, "OVERLAY_TOGGLE key=" + key + " checked=" + checked);
        if (pendingToggleOperations.contains(key)) return;
        if (!pendingToggleOperations.isEmpty()) {
            setToggleChecked(key, prefs.getBoolean("feature_" + key, false));
            message(null, "Outra função está sendo aplicada; aguarde o resultado no LOG");
            return;
        }
        if ("safe_mode".equals(key)) {
            handleSafeModeToggle(checked);
            return;
        }
        if ("brevent".equals(key)) {
            setToggleChecked(key, false);
            tryActivateBrevent();
            return;
        }
        if ("hide_for_capture".equals(key)) {
            boolean applied = setCaptureProtection(checked);
            if (applied) {
                hiddenForCapture = checked;
                setFeatureState(key, checked);
                prefs.edit().putBoolean("overlay_hidden", checked).apply();
                setToggleChecked(key, checked);
                updateForegroundNotification();
                postMessage(true, checked
                        ? "Painel oculto na transmissão; jogo continua visível"
                        : "Painel visível novamente; transmissão não foi bloqueada");
            } else {
                setToggleChecked(key, hiddenForCapture);
                postMessage(false, "Ocultar na transmissão não suportado pelo Android: o estado de gravação externa não é exposto por API pública confirmável");
            }
            return;
        }
        if ("fullscreen_stretch".equals(key)) {
            handleFullscreenToggle(checked);
            return;
        }
        if (!("touch".equals(key) || "system".equals(key) || isShellToggle(key))) {
            message((checked ? "Ativado: " : "Desativado: ") + key);
            return;
        }
        boolean previousState = prefs.getBoolean("feature_" + key, false);
        pendingToggleOperations.add(key);
        message("Aplicando " + key + "…");
        updateCapabilityStates();
        if ("touch".equals(key) || "system".equals(key)) {
            applySafe(key, checked, previousState);
        } else {
            applyShellToggle(key, checked, previousState);
        }
    }

    // Uma única opção desliga os ajustes de desempenho já ativos. O switch
    // permanece ocupado até a fila remota e a restauração do display
    // terminarem com releituras confirmadas.
    private void handleSafeModeToggle(boolean enabled) {
        if (destroyed || pendingToggleOperations.contains("safe_mode")) return;
        if (!enabled) {
            setFeatureState("safe_mode", false);
            setToggleChecked("safe_mode", false);
            postMessage(null, "Modo de proteção desativado; nenhum tweak foi reativado");
            return;
        }
        pendingToggleOperations.add("safe_mode");
        setFeatureState("safe_mode", true);
        updateCapabilityStates();
        message(null, "Desativando boosts e otimizações ativos; aguarde os resultados no LOG…");
        restoreManagedPreferences(true);
    }

    private boolean isShellToggle(String key) {
        return "cpu_governor".equals(key) || "gpu_turbo".equals(key)
                || "touch_driver".equals(key) || "network".equals(key)
                || "game_mode".equals(key) || "doze_blocker".equals(key)
                || "do_not_disturb".equals(key) || "thermal".equals(key);
    }

    private int[] fullscreenTargetFromUi() {
        FlagshipPreset flagship = selectedFlagshipPreset();
        if (flagship != null) return flagshipTarget(flagship);
        if (resolutionSelector != null && !resolutionProfiles.isEmpty()) {
            DisplayProfile profile = resolutionProfiles.get(Math.max(0, Math.min(
                    resolutionProfiles.size() - 1, resolutionSelector.getSelectedItemPosition())));
            return new int[]{profile.width, profile.height};
        }
        int savedWidth = prefs.getInt("confirmed_display_width", 0);
        int savedHeight = prefs.getInt("confirmed_display_height", 0);
        return savedWidth > 0 && savedHeight > 0
                ? new int[]{savedWidth, savedHeight}
                : new int[]{physicalDisplayWidth, physicalDisplayHeight};
    }

    private int fullscreenDensityFromUi() {
        if (dpiSelector != null && !densityProfiles.isEmpty()) {
            return densityProfiles.get(Math.max(0, Math.min(
                    densityProfiles.size() - 1, dpiSelector.getSelectedItemPosition())));
        }
        int saved = prefs.getInt("confirmed_display_density", 0);
        return saved > 0 ? saved : physicalDisplayDensity;
    }

    private void handleFullscreenToggle(boolean enabled) {
        if (destroyed) return;
        if (!hasActiveLicense()) {
            setToggleChecked("fullscreen_stretch", false);
            postMessage(false, "Ative uma key válida antes de aplicar Tela cheia");
            return;
        }
        if (enabled) {
            if (!beginRemoteOperation("fullscreen_stretch")) {
                setToggleChecked("fullscreen_stretch", false);
                return;
            }
            final int[] target = fullscreenTargetFromUi();
            final int density = fullscreenDensityFromUi();
            final String gamePackage = selectedGamePackage();
            final long generation = ++displayOperationGeneration;
            message("Aplicando Tela cheia sem resetar a resolução selecionada…");
            DisplayManagerHelper.applyFullscreen(this, target[0], target[1], density,
                    gamePackage, (ok, msg) -> {
                        if (destroyed || generation != displayOperationGeneration) return;
                        finishRemoteOperation("fullscreen_stretch");
                        if (ok) {
                            setFeatureState("fullscreen_stretch", true);
                            setToggleChecked("fullscreen_stretch", true);
                        } else {
                            setToggleChecked("fullscreen_stretch",
                                    prefs.getBoolean("feature_fullscreen_stretch", false));
                        }
                        postMessage(ok, msg);
                    });
        } else {
            restoreOriginalDisplayState();
        }
    }

    private void applyShellToggle(String key, boolean enabled, boolean previousState) {
        Log.d(FLOW_TAG, "OVERLAY_SHELL_TOGGLE_START key=" + key); // LOG ADICIONADO
        ShellManager.Callback callback = (ok, msg) -> {
            if (destroyed) return;
            pendingToggleOperations.remove(key);
            if (ok) {
                // Só agora o estado confirmado vira persistente.
                setFeatureState(key, enabled);
                setToggleChecked(key, enabled);
            } else {
                // CORRIGIDO: erro de uma tentativa não bloqueia permanentemente
                // CPU/GPU/touch; a opção continua disponível para nova tentativa.
                unavailableShellFeatures.remove(key);
                setFeatureState(key, previousState);
                setToggleChecked(key, previousState);
            }
            updateCapabilityStates();
            postMessage(ok, msg);
        };
        if ("cpu_governor".equals(key)) ShellManager.setCpuGovernor(this, enabled, callback);
        else if ("gpu_turbo".equals(key)) ShellManager.setGpuTurbo(this, enabled, callback);
        else if ("touch_driver".equals(key)) ShellManager.setTouchOptimization(this, enabled, callback);
        else if ("network".equals(key)) ShellManager.setNetworkOptimization(this, enabled, callback);
        else if ("game_mode".equals(key)) ShellManager.setGameMode(this, enabled, callback);
        else if ("doze_blocker".equals(key)) ShellManager.setDozeBlocker(this, enabled, callback);
        else if ("do_not_disturb".equals(key)) ShellManager.setDoNotDisturb(this, enabled, callback);
        else if ("thermal".equals(key)) ShellManager.setThermalOptimization(this, enabled, callback);
    }

    private boolean isPermanentCapabilityFailure(String key, String message) {
        // CORRIGIDO: exit code diferente de zero, permissão ou binder morto
        // são falhas da tentativa, não prova de incompatibilidade permanente.
        if ("game_mode".equals(key) && message != null && message.contains("Abra o jogo")) return false;
        if ("doze_blocker".equals(key) && message != null && message.contains("Abra o jogo")) return false;
        return message != null && (message.contains("não suportado")
                || message.contains("não está disponível")
                || message.contains("não expõe")
                || message.contains("não ajustável"));
    }

    private void setFeatureState(String key, boolean enabled) {
        prefs.edit().putBoolean("feature_" + key, enabled).apply();
    }

    private void setToggleChecked(String key, boolean checked) {
        Switch toggle = toggleViews.get(key);
        if (toggle == null) return;
        updatingControls = true;
        toggle.setChecked(checked);
        updatingControls = false;
    }

    private void applySafe() {
        applySafe(null, false, false);
    }

    private void applySafeSlider(String key, int requestedValue, int previousValue,
                                 int min, String title) {
        Log.d(FLOW_TAG, "OVERLAY_SLIDER_APPLY key=" + key + " path=remote value=" + requestedValue);
        final String pendingKey = "slider_" + key;
        pendingToggleOperations.add(pendingKey);
        int press = key.equals("long_press") ? requestedValue
                : prefs.getInt("slider_long_press", 400);
        int scale = key.equals("animation_scale") ? requestedValue
                : prefs.getInt("slider_animation_scale", 100);
        boolean touch = prefs.getBoolean("feature_touch", false)
                || key.equals("long_press");
        boolean system = prefs.getBoolean("feature_system", false)
                || key.equals("animation_scale");
        ShizukuBridge.applySafeSettings(this, touch, system, press, scale,
                (ok, msg) -> {
                    if (destroyed) return; // CORRIGIDO BUG1: callback tardio não toca SeekBar/estado após destruir a overlay.
                    pendingToggleOperations.remove(pendingKey);
                    if (ok) {
                        prefs.edit().putInt(pendingKey, requestedValue).apply();
                        pendingSliderPrevious.remove(pendingKey);
                    } else {
                        SeekBar seek = sliderViews.get(key);
                        if (seek != null) {
                            updatingControls = true;
                            seek.setProgress(previousValue - min);
                            updatingControls = false;
                        }
                        pendingSliderPrevious.remove(pendingKey);
                    }
                    updateCapabilityStates();
                    postMessage(ok, ok ? title + " aplicado e confirmado" : msg);
                });
    }

    private void applySafe(String changedKey, boolean requestedState, boolean previousState) {
        // CORRIGIDO: deixar a operação chegar ao worker; o snapshot Main pode
        // estar atrasado imediatamente depois da autorização Shizuku.
        boolean touch = "touch".equals(changedKey) ? requestedState
                : prefs.getBoolean("feature_touch", false);
        boolean system = "system".equals(changedKey) ? requestedState
                : prefs.getBoolean("feature_system", false);
        int press = prefs.getInt("slider_long_press", 400);
        int scale = prefs.getInt("slider_animation_scale", 100);
        ShizukuBridge.applySafeSettings(this, touch, system, press, scale,
                (ok, msg) -> {
                    if (destroyed) return;
                    if (changedKey != null) {
                        pendingToggleOperations.remove(changedKey);
                        setFeatureState(changedKey, ok ? requestedState : previousState);
                        setToggleChecked(changedKey, ok ? requestedState : previousState);
                    }
                    updateCapabilityStates();
                    postMessage(ok, msg);
                });
    }

    private void applyRefresh(int hz) {
        Display.Mode best = null;
        for (Display.Mode mode : supportedModes()) {
            if (Math.abs(mode.getRefreshRate() - hz) <= 1.5f) {
                if (best == null || Math.abs(mode.getRefreshRate() - hz)
                        < Math.abs(best.getRefreshRate() - hz)) best = mode;
            }
        }
        if (best == null) {
            postMessage(false, hz + " Hz não disponível neste display");
            return;
        }
        if (!beginRemoteOperation("refresh_rate")) return;
        final Display.Mode chosenMode = best;
        Display currentBefore = primaryDisplay();
        if (currentBefore != null && currentBefore.getMode() != null
                && !prefs.contains("original_display_mode_id")) {
            prefs.edit().putInt("original_display_mode_id",
                    currentBefore.getMode().getModeId()).apply();
        }
        // Primeiro altera a taxa global somente se o firmware expõe os
        // settings oficiais; depois aplica o mesmo modo à janela do painel.
        ShellManager.setGlobalRefreshRate(this, hz, (ok, msg) -> {
            if (destroyed) return;
            if (!ok) {
                finishRemoteOperation("refresh_rate");
                postMessage(false, msg);
                return;
            }
            boolean panelApplied = false;
            if (panelParams != null) {
                panelParams.preferredDisplayModeId = chosenMode.getModeId();
                panelApplied = updateWindowLayoutSafely();
            }
            Display effective = primaryDisplay();
            boolean modeReadback = panelApplied && effective != null && effective.getMode() != null
                    && effective.getMode().getModeId() == chosenMode.getModeId();
            if (!modeReadback) {
                int originalMode = prefs.getInt("original_display_mode_id", 0);
                if (panelParams != null) {
                    panelParams.preferredDisplayModeId = originalMode;
                    updateWindowLayoutSafely();
                }
                ShellManager.restoreGlobalRefreshRate(this, (rollbackOk, rollbackMsg) -> {
                    if (destroyed) return;
                    finishRemoteOperation("refresh_rate");
                    postMessage(false, "Modo efetivo não confirmou Display.Mode "
                            + chosenMode.getModeId() + "; rollback=" + rollbackOk + " · " + rollbackMsg);
                });
                return;
            }
            prefs.edit().putInt("applied_display_mode_id", chosenMode.getModeId())
                    .putFloat("applied_display_rate", chosenMode.getRefreshRate())
                    .putInt("selected_refresh_hz", hz).apply();
            updateRefreshStatus();
            finishRemoteOperation("refresh_rate");
            postMessage(true, msg + " · modo do painel confirmado");
        });
    }

    private boolean restoreOriginalPanelDisplayMode() {
        if (panelParams == null) return true;
        int original = prefs.getInt("original_display_mode_id", 0);
        panelParams.preferredDisplayModeId = original;
        boolean layoutConfirmed = !windowAdded || updateWindowLayoutSafely();
        Display display = primaryDisplay();
        boolean modeConfirmed = original <= 0 || (display != null && display.getMode() != null
                && display.getMode().getModeId() == original);
        boolean confirmed = layoutConfirmed && modeConfirmed;
        if (confirmed) {
            prefs.edit().remove("original_display_mode_id")
                    .remove("applied_display_mode_id")
                    .remove("applied_display_rate").apply();
        }
        return confirmed;
    }

    private void resetOverscan() {
        if (!overscanSupported) {
            // CORRIGIDO: não chamar comando inexistente em firmware sem suporte.
            message("Overscan não suportado neste firmware");
            return;
        }
        ShizukuBridge.resetOverscan(this, (ok, msg) -> {
            // CORRIGIDO: callback já chega na Main Looper pela ponte.
            if (destroyed) return;
            postMessage(ok, msg);
            if (panel != null) panel.postDelayed(this::updateCapabilityStates, 400);
        });
    }

    private void captureOriginalDisplayState() {
        if (!beginRemoteOperation("display_snapshot")) return;
        saveOverlayDisplaySnapshot();
        final int x = stretchXSlider == null ? prefs.getInt("slider_stretch_x", 0)
                : stretchXSlider.getProgress();
        final int y = stretchYSlider == null ? prefs.getInt("slider_stretch_y", 0)
                : stretchYSlider.getProgress();
        DisplayManagerHelper.captureSnapshot(this, (ok, msg) -> {
            if (destroyed) return;
            if (ok) {
                prefs.edit().putInt("stretch_preset_x", Math.max(0, Math.min(100, x)))
                        .putInt("stretch_preset_y", Math.max(0, Math.min(10, y)))
                        .putString("stretch_preset_name", "Stretch Resolution")
                        .putInt("stretch_preset_version", 1)
                        .apply();
                postMessage(true, msg + " · preset Stretch Resolution salvo X=" + x + " Y=" + y);
            } else {
                postMessage(false, msg);
            }
            finishRemoteOperation("display_snapshot");
            updateCapabilityStates();
        });
    }

    private void restoreStretchSliderPreset() {
        int x = Math.max(0, Math.min(100, prefs.getInt("stretch_preset_x", 0)));
        int y = Math.max(0, Math.min(10, prefs.getInt("stretch_preset_y", 0)));
        if (stretchXSlider != null) {
            updatingControls = true;
            stretchXSlider.setProgress(x);
            updatingControls = false;
        }
        if (stretchYSlider != null) {
            updatingControls = true;
            stretchYSlider.setProgress(y);
            updatingControls = false;
        }
        prefs.edit().putInt("slider_stretch_x", x).putInt("slider_stretch_y", y).apply();
        Log.d("DISPLAY_DEBUG", "STRETCH_PRESET_READBACK x=" + x + " y=" + y);
    }

    private void restoreOriginalDisplayState() {
        if (destroyed || !beginRemoteOperation("display_restore")) return;
        final long generation = ++displayOperationGeneration;
        DisplayManagerHelper.restoreSavedSnapshot(this, (ok, msg) -> {
            if (destroyed || generation != displayOperationGeneration) return;
            finishRemoteOperation("display_restore");
            if (ok) {
                setFeatureState("fullscreen_stretch", false);
                setToggleChecked("fullscreen_stretch", false);
                postMessage(true, "Estado original restaurado por readback: " + msg);
            } else {
                setToggleChecked("fullscreen_stretch",
                        prefs.getBoolean("feature_fullscreen_stretch", false));
                postMessage(false, msg);
            }
        });
    }

    private boolean vulkanHardwareAvailable() {
        PackageManager manager = getPackageManager();
        return manager.hasSystemFeature("android.hardware.vulkan.level")
                || manager.hasSystemFeature("android.hardware.vulkan.version");
    }

    private boolean vulkanAndroidSupported() {
        return Build.VERSION.SDK_INT >= 24 && vulkanHardwareAvailable();
    }

    private String vulkanStatus() {
        return "Vulkan hardware: " + (vulkanHardwareAvailable() ? "exposto" : "não exposto")
                + " · Vulkan Android: " + (vulkanAndroidSupported() ? "suportado" : "não confirmado")
                + " · renderer externo não suportado";
    }

    private boolean openGlAvailable() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ConfigurationInfo info = activityManager == null ? null
                : activityManager.getDeviceConfigurationInfo();
        return info != null && info.reqGlEsVersion >= 0x00020000;
    }

    private String openGlStatus() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ConfigurationInfo info = activityManager == null ? null
                : activityManager.getDeviceConfigurationInfo();
        if (!openGlAvailable()) return "OpenGL ES não disponível ou não informado pelo aparelho";
        int version = info == null ? 0 : info.reqGlEsVersion;
        return String.format(Locale.US,
                "OpenGL ES %d.%d exposto; aplicação em app externo não suportada",
                (version >> 16) & 0xffff, version & 0xffff);
    }

    private String skiaStatus() {
        // Android Views são desenhadas pelo framework Skia; não existe API
        // pública para impor Skia/Vulkan/OpenGL em outro aplicativo ou jogo.
        return "Skia é backend do Android; renderer externo não suportado neste painel";
    }

    private String permissionStatus() {
        String overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
                ? "concedido" : "pendente";
        String notification = Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED ? "concedida" : "pendente";
        String writeSettings = Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(this)
                ? "concedido" : "pendente";
        String secure = hasSecureSettingsPermission()
                ? "Permissão concedida — funciona permanentemente, sem internet"
                : "WRITE_SECURE_SETTINGS: pendente · ADB: pm grant painel.sensi.santos android.permission.WRITE_SECURE_SETTINGS";
        return "Sobreposição: " + overlay + " · notificações: " + notification
                + " · WRITE_SETTINGS: " + writeSettings + "\n" + secure
                + "\nShizuku: toque em AUTORIZAR · Brevent: toque no switch";
    }

    private void refreshDeviceInfo() {
        if (destroyed || deviceInfo == null) return;
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
                .format(new java.util.Date());
        StringBuilder modes = new StringBuilder();
        for (Display.Mode mode : supportedModes()) {
            if (mode == null) continue;
            String hz = String.format(Locale.US, "%.2f Hz", mode.getRefreshRate());
            if (modes.indexOf(hz) < 0) {
                if (modes.length() > 0) modes.append(", ");
                modes.append(hz);
            }
        }
        Display display = primaryDisplay();
        String physical = "não lida";
        String logical = "não lida";
        String dpiPhysical = "não lido";
        String dpiLogical = "não lido";
        String rotation = "não lida";
        String displayId = "não lido";
        try {
            if (display != null) {
                android.graphics.Point real = new android.graphics.Point();
                android.graphics.Point logicalPoint = new android.graphics.Point();
                android.util.DisplayMetrics realMetrics = new android.util.DisplayMetrics();
                android.util.DisplayMetrics logicalMetrics = new android.util.DisplayMetrics();
                display.getRealSize(real);
                display.getSize(logicalPoint);
                display.getRealMetrics(realMetrics);
                display.getMetrics(logicalMetrics);
                physical = real.x + " x " + real.y;
                logical = logicalPoint.x + " x " + logicalPoint.y;
                dpiPhysical = String.valueOf(realMetrics.densityDpi);
                dpiLogical = String.valueOf(logicalMetrics.densityDpi);
                rotation = String.valueOf(display.getRotation());
                displayId = String.valueOf(display.getDisplayId());
            }
        } catch (Throwable error) {
            Log.w("DEVICE_INFO", "DISPLAY_API_READ_FAILED", error);
        }
        boolean breventInstalled = isBreventInstalled();
        String oneUi = "Samsung".equalsIgnoreCase(Build.MANUFACTURER)
                ? "não exposta pela API pública" : "não aplicável/não exposta";
        String output = "Fonte=Android API; comando=Build/DisplayManager/PackageManager/Settings; exit=0; stdout=leitura local; stderr=; horário=" + stamp
                + "\nAndroid=" + Build.VERSION.RELEASE + " · SDK=" + Build.VERSION.SDK_INT
                + " · fabricante=" + Build.MANUFACTURER + " · modelo=" + Build.MODEL
                + "\nBuild=" + Build.DISPLAY + " · One UI=" + oneUi
                + "\nResolução física=" + physical + " · lógica=" + logical
                + "\nDPI físico=" + dpiPhysical + " · lógico=" + dpiLogical
                + " · rotação=" + rotation + " · display id=" + displayId
                + "\nModos Hz confirmados=" + (modes.length() == 0 ? "não informados" : modes)
                + "\n" + vulkanStatus() + "\n" + openGlStatus()
                + "\nSobreposição=" + (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this) ? "concedida" : "pendente")
                + " · WRITE_SECURE_SETTINGS=" + (hasSecureSettingsPermission() ? "concedida" : "pendente")
                + "\nShizuku=" + ShizukuBridge.status() + " · Brevent instalado=" + breventInstalled
                + "\nPackage selecionado=" + (selectedGamePackage().isEmpty() ? "nenhum" : selectedGamePackage());
        deviceInfo.setText(output);
        Log.d("DEVICE_INFO", output.replace('\n', ' '));
    }

    private void applyAccentColor(int color) {
        int previous = panelAccentColor;
        if (previous == color) return;
        if (!prefs.contains("original_panel_accent_color")) {
            prefs.edit().putInt("original_panel_accent_color", previous).apply();
        }
        panelAccentColor = color;
        prefs.edit().putInt("panel_accent_color", color).apply();
        applyAccentToViews(panel, color, previous);
    }

    private void applyAccentToViews(View view, int color, int previous) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            int current = textView.getCurrentTextColor();
            if ("accent_button".equals(textView.getTag())) {
                textView.setTextColor(accentForeground(color));
            } else if (current == previous || current == Ui.BRIGHT) {
                textView.setTextColor(color);
            }
        }
        if (view instanceof ImageView && "accent_icon".equals(view.getTag())) {
            ((ImageView) view).setColorFilter(color);
        }
        if (view instanceof Switch && Build.VERSION.SDK_INT >= 21) {
            Switch toggle = (Switch) view;
            int[][] states = new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}};
            toggle.setThumbTintList(new ColorStateList(states, new int[]{color, Ui.MUTED}));
            toggle.setTrackTintList(new ColorStateList(states, new int[]{(color & 0x00FFFFFF) | 0x99000000, 0x66526A83}));
        }
        if (view.getBackground() != null) {
            if ("accent_button".equals(view.getTag())) {
                view.setBackground(Ui.rounded(color, 9, this));
            } else {
                view.getBackground().setColorFilter(new PorterDuffColorFilter(
                        (color & 0x00FFFFFF) | 0x1F000000, PorterDuff.Mode.SRC_ATOP));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyAccentToViews(group.getChildAt(i), color, previous);
            }
        }
    }

    private boolean restorePanelAccentColor() {
        if (destroyed) return false;
        int original = prefs.getInt("original_panel_accent_color", Ui.BRIGHT);
        int current = prefs.getInt("panel_accent_color", Ui.BRIGHT);
        panelAccentColor = original;
        prefs.edit().remove("panel_accent_color").remove("original_panel_accent_color").apply();
        applyAccentToViews(panel, original, current);
        if (colorWheel != null) colorWheel.setColor(original);
        if (colorStatus != null) {
            colorStatus.setText("Cor restaurada: #" + String.format(Locale.US, "%06X", original & 0xFFFFFF));
            colorStatus.setTextColor(original);
        }
        return true;
    }

    private void restoreManagedPreferences() {
        restoreManagedPreferences(false);
    }

    /**
     * Orquestra a restauração em sequência: ponte remota serializada primeiro,
     * depois display (size → density → settings → compat → releitura). O
     * switch safe_mode só é concluído no callback final confirmado.
     */
    private void restoreManagedPreferences(final boolean protectionMode) {
        if (destroyed) return;
        if (!pendingToggleOperations.contains("safe_mode")) {
            pendingToggleOperations.add("safe_mode");
        }
        final String savedKey = prefs.getString("key", "");
        restorePointerSpeedLocal();
        message(null, "Fila única de restauração iniciada; Thermal preservado…");
        ShellManager.restoreAllTweaks(this, (shellOk, shellMsg) -> {
            if (destroyed) return;
            postMessage(shellOk, "Fila Shizuku concluída:\n" + shellMsg);
            // Só depois que a fila remota terminou, inicia o lock único do
            // display. Nunca há size/density ou rollback concorrente.
            DisplayManagerHelper.applyStretchPreset(this, DisplayManagerHelper.Preset.DEFAULT,
                    1.00f, (displayOk, displayMsg) -> {
                        if (destroyed) return;
                        postMessage(displayOk, "Display: " + displayMsg);
                        boolean captureOk = !hiddenForCapture || setCaptureProtection(false);
                        if (captureOk) {
                            hiddenForCapture = false;
                            setFeatureState("hide_for_capture", false);
                        }
                        boolean allOk = shellOk && displayOk && captureOk;
                        if (allOk) {
                            boolean colorOk = restorePanelAccentColor();
                            allOk = allOk && colorOk;
                            SharedPreferences.Editor editor = prefs.edit();
                            String[] controlled = {
                                    "feature_touch", "feature_system", "feature_cpu_governor",
                                    "feature_gpu_turbo", "feature_touch_driver", "feature_network",
                                    "feature_game_mode", "feature_doze_blocker",
                                    "feature_do_not_disturb", "feature_safe_mode",
                                    "feature_hide_for_capture", "feature_cache", "feature_ram",
                                    "feature_stretch", "feature_fullscreen_stretch",
                                    "slider_long_press", "slider_animation_scale",
                                    "slider_stretch_x", "slider_stretch_y",
                                    "stretch_preset_x", "stretch_preset_y",
                                    "applied_display_mode_id", "applied_display_rate",
                                    "original_display_mode_id", "stretch_requested", "stretch_factor", "renderer_mode",
                                    "original_panel_accent_color", "panel_accent_color"
                            };
                            for (String name : controlled) editor.remove(name);
                            editor.putString("key", savedKey).apply();
                            for (String keyName : toggleViews.keySet()) {
                                if (!"thermal".equals(keyName)) setToggleChecked(keyName, false);
                            }
                            for (Map.Entry<String, SeekBar> entry : sliderViews.entrySet()) {
                                if ("pointer_speed".equals(entry.getKey())) continue;
                                Integer min = sliderMins.get(entry.getKey());
                                Integer defaultValue = sliderDefaults.get(entry.getKey());
                                if (min == null || defaultValue == null) continue;
                                updatingControls = true;
                                entry.getValue().setProgress(defaultValue - min);
                                updatingControls = false;
                                TextView value = sliderValues.get(entry.getKey());
                                if (value != null) value.setText(formatSliderValue(entry.getKey(), defaultValue));
                            }
                            if (rendererSelector != null) rendererSelector.setSelection(0);
                            restoreOriginalPanelDisplayMode();
                            setFeatureState("safe_mode", protectionMode);
                            setToggleChecked("safe_mode", protectionMode);
                        } else {
                            // Falha parcial nunca vira sucesso: conservar as
                            // preferências/estados para nova tentativa e deixar
                            // o estado real visível no LOG.
                            setFeatureState("safe_mode", false);
                            setToggleChecked("safe_mode", false);
                        }
                        pendingToggleOperations.remove("safe_mode");
                        updateRefreshStatus();
                        updateCapabilityStates();
                        postMessage(allOk, allOk
                                ? (protectionMode ? "Modo de proteção concluído após releitura completa"
                                : "Restauração total concluída após releitura completa")
                                : "Restauração parcial/recusada; estados reais foram mantidos para nova tentativa");
                    });
        });
    }

    private int panelDp(int value) {
        return Math.max(1, Math.round(value * panelBaseDensity));
    }

    private int systemBarsTopAndBottom() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
                android.graphics.Insets insets = metrics.getWindowInsets()
                        .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());
                return insets.top + insets.bottom;
            } catch (Throwable ignored) { }
        }
        return panelDp(24);
    }

    private int screenWidthPx() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return wm.getCurrentWindowMetrics().getBounds().width();
            }
            Display display = wm.getDefaultDisplay();
            if (display != null) {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                display.getRealMetrics(metrics);
                return metrics.widthPixels;
            }
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "OVERLAY_SCREEN_WIDTH_READ_FAILED", error); // TRY/CATCH ADICIONADO
        }
        return Math.max(panelDp(320), getResources().getDisplayMetrics().widthPixels);
    }

    private int screenHeightPx() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return wm.getCurrentWindowMetrics().getBounds().height();
            }
            Display display = wm.getDefaultDisplay();
            if (display != null) {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                display.getRealMetrics(metrics);
                return metrics.heightPixels;
            }
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "OVERLAY_SCREEN_HEIGHT_READ_FAILED", error); // TRY/CATCH ADICIONADO
        }
        return Math.max(panelDp(320), getResources().getDisplayMetrics().heightPixels);
    }

    private int usefulPanelHeightPx() {
        int margin = panelDp(10);
        return Math.max(panelDp(220),
                Math.min(panelDp(PANEL_MAX_HEIGHT_DP), screenHeightPx() - systemBarsTopAndBottom() - margin * 2));
    }

    private void saveOverlayPositionState() {
        if (prefs == null || panelParams == null) return;
        prefs.edit().putInt("overlay_x", panelParams.x)
                .putInt("overlay_y", panelParams.y)
                .putBoolean("overlay_collapsed", collapsed)
                .putBoolean("overlay_hidden", hiddenForCapture).apply();
        Log.d("DISPLAY_DEBUG", "OVERLAY_POSITION_SAVED x=" + panelParams.x
                + " y=" + panelParams.y + " collapsed=" + collapsed);
    }

    private void clampPanelPosition(int width, int height) {
        int margin = panelDp(10);
        int maxX = Math.max(margin, screenWidthPx() - width - margin);
        int maxY = Math.max(margin, screenHeightPx() - height - margin
                - (Build.VERSION.SDK_INT >= 30 ? panelDp(4) : 0));
        panelParams.x = Math.max(margin, Math.min(panelParams.x, maxX));
        panelParams.y = Math.max(margin, Math.min(panelParams.y, maxY));
    }

    private boolean updateWindowLayoutSafely() {
        if (destroyed || wm == null || panel == null || panelParams == null
                || !windowAdded || !panel.isAttachedToWindow()) {
            return false;
        }
        try {
            wm.updateViewLayout(panel, panelParams);
            return true;
        } catch (Throwable error) {
            // Só invalidar a flag quando a View realmente deixou de estar
            // anexada; se ainda estiver anexada, showPanel não tentará addView.
            if (!panel.isAttachedToWindow()) windowAdded = false;
            Log.w("OverlayService", "Falha atualizando janela do overlay", error);
            return false;
        }
    }

    private void saveOverlayDisplaySnapshot() {
        if (prefs == null) return;
        try {
            prefs.edit().putInt("display_snapshot_id", Display.DEFAULT_DISPLAY)
                    .putInt("display_snapshot_rotation", currentDisplayRotation)
                    .putInt("display_snapshot_tab", selectedTab)
                    .putInt("display_snapshot_color", panelAccentColor)
                    .putString("display_snapshot_package", selectedGamePackage())
                    .putBoolean("display_snapshot_hidden", hiddenForCapture)
                    .putInt("display_snapshot_x", panelParams == null ? 0 : panelParams.x)
                    .putInt("display_snapshot_y", panelParams == null ? 0 : panelParams.y)
                    .putBoolean("display_snapshot_collapsed", collapsed)
                    .putBoolean("display_snapshot_secure", hiddenForCapture)
                    .apply();
            Log.d("DISPLAY_DEBUG", "OVERLAY_SNAPSHOT id=" + Display.DEFAULT_DISPLAY
                    + " rotation=" + currentDisplayRotation + " x="
                    + (panelParams == null ? 0 : panelParams.x) + " y="
                    + (panelParams == null ? 0 : panelParams.y) + " collapsed=" + collapsed
                    + " secure=" + hiddenForCapture);
        } catch (Throwable error) {
            Log.e("DISPLAY_DEBUG", "OVERLAY_SNAPSHOT_FAILED", error);
        }
    }

    // CORRIGIDO: em erro do display, restaura também o estado confirmado da
    // janela/bolha; a View nunca é adicionada ou removida neste caminho.
    private void restoreOverlayDisplaySnapshot() {
        if (destroyed || prefs == null || panelParams == null) return;
        try {
            if (prefs.getInt("display_snapshot_id", -1) != Display.DEFAULT_DISPLAY) return;
            collapsed = prefs.getBoolean("display_snapshot_collapsed", collapsed);
            selectedTab = Math.max(0, Math.min(4,
                    prefs.getInt("display_snapshot_tab", selectedTab)));
            panelAccentColor = prefs.getInt("display_snapshot_color", panelAccentColor);
            String savedPackage = prefs.getString("display_snapshot_package", "");
            if (gamePackageInput != null) gamePackageInput.setText(savedPackage);
            if (!savedPackage.isEmpty()) prefs.edit().putString("selected_game_package", savedPackage).apply();
            else prefs.edit().remove("selected_game_package").apply();
            prefs.edit().putInt("panel_accent_color", panelAccentColor).apply();
            int savedX = prefs.getInt("display_snapshot_x", panelParams.x);
            int savedY = prefs.getInt("display_snapshot_y", panelParams.y);
            panelParams.x = savedX;
            panelParams.y = savedY;
            boolean secure = prefs.getBoolean("display_snapshot_secure", false);
            hiddenForCapture = false;
            prefs.edit().putBoolean("overlay_hidden", false).putBoolean("feature_hide_for_capture", false).apply();
            if (secure) Log.i("CAPTURE_DEBUG", "SNAPSHOT_CAPTURE_HIDDEN_IGNORED external recording state unsupported");
            setCaptureProtection(false);
            applyAccentToViews(panel, panelAccentColor, panelAccentColor);
            resizePanel();
            selectTab(selectedTab);
            Log.d("DISPLAY_DEBUG", "OVERLAY_SNAPSHOT_RESTORED x=" + savedX
                    + " y=" + savedY + " collapsed=" + collapsed + " secure=" + secure);
        } catch (Throwable error) {
            Log.e("DISPLAY_DEBUG", "OVERLAY_SNAPSHOT_RESTORE_FAILED", error);
        }
    }

    private boolean setCaptureProtection(boolean enabled) {
        if (destroyed || panel == null) return false;
        if (enabled) {
            Log.i("CAPTURE_DEBUG", "CAPTURE_EXTERNAL_STATE_UNSUPPORTED Android não expõe sessão externa confirmável");
            return false;
        }
        int previousVisibility = panel.getVisibility();
        int requestedVisibility = enabled ? View.INVISIBLE : View.VISIBLE;
        try {
            // CORRIGIDO: somente oculta o conteúdo da janela; a transmissão
            // continua exibindo o jogo, sem bloquear a captura e sem tela preta.
            panel.setVisibility(requestedVisibility);
            boolean confirmed = panel.getVisibility() == requestedVisibility;
            Log.d("CAPTURE_DEBUG", "PANEL_VISIBILITY requested=" + requestedVisibility
                    + " confirmed=" + confirmed + " attached=" + panel.isAttachedToWindow());
            if (!confirmed) panel.setVisibility(previousVisibility);
            return confirmed;
        } catch (Throwable error) {
            try { panel.setVisibility(previousVisibility); } catch (Throwable ignored) { }
            Log.e("CAPTURE_DEBUG", "PANEL_VISIBILITY_FAILED", error);
            return false;
        }
    }

    private void resizePanel() {
        if (destroyed || panel == null || panelBody == null || bubble == null
                || panelParams == null || wm == null) return;
        panelBody.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        bubble.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        if (collapsed) {
            panelParams.width = panelDp(BUBBLE_SIZE_DP);
            panelParams.height = panelDp(BUBBLE_SIZE_DP);
        } else {
            panelParams.width = Math.min(panelDp(PANEL_WIDTH_DP),
                    Math.max(panelDp(280), screenWidthPx() - panelDp(20)));
            panelParams.height = usefulPanelHeightPx();
        }
        clampPanelPosition(panelParams.width, panelParams.height);
        updateWindowLayoutSafely();
    }

    private void markOverlayReady() {
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean("overlay_ready", true)
                .remove("overlay_error");
        // A notificação de restaurar não carrega request_id; nesse caso não
        // sobrescrevemos o identificador da última abertura da Activity.
        if (requestId > 0L) editor.putLong("overlay_ready_request_id", requestId);
        editor.apply();
    }

    private void showPanel() {
        Log.d(FLOW_TAG, "OVERLAY_SHOW_PANEL_REQUEST attached="
                + (panel != null && panel.isAttachedToWindow())); // LOG ADICIONADO
        if (destroyed || panel == null || wm == null) return;
        cancelPendingClose();
        if (panel.isAttachedToWindow()) {
            // CORRIGIDO: se o Android ainda considera a View anexada, não tentar
            // addView novamente mesmo que a flag local esteja atrasada.
            windowAdded = true;
            markOverlayReady();
            return;
        }
        // CORRIGIDO: estado inconsistente é normalizado antes de um novo addView.
        windowAdded = false;
        try {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                throw new SecurityException("overlay permission missing");
            }
            // Recalcula dimensões/limites antes de adicionar após rotação ou reinício.
            resizePanel();
            panel.setAlpha(0f);
            wm.addView(panel, panelParams);
            Log.d(FLOW_TAG, "OVERLAY_WINDOW_ADDED type=" + panelParams.type); // LOG ADICIONADO
            // CORRIGIDO: só marcar pronto depois que addView terminou sem exceção.
            windowAdded = true;
            markOverlayReady();
            panel.animate().alpha(1f).setDuration(260).start();
            // CORRIGIDO: anexar a janela não dispara nenhuma chamada ao Shizuku.
        } catch (Throwable error) {
            Log.e(FLOW_TAG, "OVERLAY_WINDOW_ADD_FAILED", error); // TRY/CATCH ADICIONADO
            Log.e("OverlayService", "Falha ao adicionar bolha", error);
            prefs.edit().putBoolean("overlay_ready", false)
                    .putString("overlay_error", error.getClass().getSimpleName() + ": "
                            + (error.getMessage() == null ? "sem mensagem" : error.getMessage())).apply();
            windowAdded = false;
            stopSelf();
        }
    }

    private void hideForCapture() {
        if (destroyed) return;
        boolean applied = setCaptureProtection(true);
        if (!applied) {
            Log.e("CAPTURE_DEBUG", "PANEL_VISIBILITY não aplicado: gravação externa não confirmável pelo Android");
            postMessage(false, "Ocultar na transmissão não suportado pelo Android: gravação externa não confirmada");
            return;
        }
        cancelPendingClose();
        hiddenForCapture = true;
        setFeatureState("hide_for_capture", true);
        prefs.edit().putBoolean("overlay_hidden", true)
                .putString("overlay_capture_limit", "Painel invisível durante a transmissão; a captura do jogo continua normal")
                .apply();
        Log.i("CAPTURE_DEBUG", "Painel oculto durante transmissão; janela e serviço mantidos");
        updateForegroundNotification();
    }

    private void closePanel() {
        Log.d(FLOW_TAG, "OVERLAY_CLOSE_TO_KEY_SCREEN"); // LOG ADICIONADO
        cancelPendingClose();
        closeRequested = true;
        hiddenForCapture = true;
        remove(panel);
        LicenseValidator.stopMonitoring();
        try {
            Intent keyIntent = new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_KEY_SCREEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(keyIntent);
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "OVERLAY_CLOSE_KEY_SCREEN_FAILED", error); // TRY/CATCH ADICIONADO
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        if (lastStartId > 0) stopSelfResult(lastStartId);
        else stopSelf();
    }

    private void postMessage(String value) {
        postMessage((Boolean) null, value);
    }

    private void postMessage(boolean ok, String value) {
        postMessage(Boolean.valueOf(ok), value);
    }

    private void postMessage(Boolean operationOk, String value) {
        // CORRIGIDO: todo retorno de shell passa pela Main Looper e é ignorado
        // quando o serviço já foi destruído.
        if (destroyed) return;
        mainHandler.post(() -> {
            if (!destroyed) message(operationOk, value);
        });
    }

    private void message(String value) {
        message((Boolean) null, value);
    }

    // CORRIGIDO: cada mensagem é concatenada em uma nova entrada; o TextView
    // conserva stdout/stderr/exit code e rola para a última linha sem truncar.
    private void appendStatusLine(Boolean operationOk, String value) {
        if (destroyed || status == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> appendStatusLine(operationOk, value));
            return;
        }
        String safeMessage = value == null || value.trim().isEmpty()
                ? "sem mensagem" : value.trim();
        String result = operationOk == null ? "INFO"
                : (operationOk ? "OK" : "ERRO");
        statusLog.append('[').append(result).append("] ")
                .append(safeMessage).append('\n');
        status.setText(statusLog.toString());
        updateLogSummary();
        status.post(() -> {
            if (destroyed || status == null) return;
            android.text.Layout layout = status.getLayout();
            if (layout != null) {
                int bottom = layout.getLineBottom(Math.max(0, layout.getLineCount() - 1));
                status.scrollTo(0, Math.max(0, bottom - status.getHeight()));
            } else {
                status.scrollTo(0, status.getBottom());
            }
        });
    }

    private void message(Boolean operationOk, String value) {
        // CORRIGIDO: somente a Main Looper atualiza o TextView de status.
        if (destroyed || status == null) return;
        String overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)
                ? "concedido" : "pendente";
        String result = operationOk == null ? "informação"
                : (operationOk ? "sucesso" : "falha");
        String header = "Shizuku: " + ShizukuBridge.status().replace("Shizuku ", "")
                + " · Overlay: " + overlay
                + "\n" + ShizukuBridge.lastCommandStatus()
                + "\n" + result + ": " + (value == null ? "sem mensagem" : value.trim());
        appendStatusLine(operationOk, header);
        status.animate().alpha(.45f).setDuration(100).withEndAction(() -> {
            if (!destroyed && status != null) status.animate().alpha(1f).setDuration(220).start();
        }).start();
    }

    private View.OnTouchListener moveBubble() {
        return new View.OnTouchListener() {
            int initialX;
            int initialY;
            float startX;
            float startY;
            boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    initialX = panelParams.x;
                    initialY = panelParams.y;
                    startX = event.getRawX();
                    startY = event.getRawY();
                    moved = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    int dx = (int) (event.getRawX() - startX);
                    int dy = (int) (event.getRawY() - startY);
                    if (Math.abs(dx) > Ui.dp(OverlayService.this, 6)
                            || Math.abs(dy) > Ui.dp(OverlayService.this, 6)) moved = true;
                    if (moved && !destroyed && panelParams != null) {
                        panelParams.x = initialX + dx;
                        panelParams.y = initialY + dy;
                        clampPanelPosition(panelParams.width, panelParams.height);
                        updateWindowLayoutSafely();
                    }
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (moved) saveOverlayPositionState();
                    else view.performClick();
                    return true;
                }
                return true;
            }
        };
    }

    private View.OnTouchListener movePanel() {
        return new View.OnTouchListener() {
            int initialX;
            int initialY;
            float startX;
            float startY;
            boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    initialX = panelParams.x;
                    initialY = panelParams.y;
                    startX = event.getRawX();
                    startY = event.getRawY();
                    moved = false;
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_MOVE) {
                    if (destroyed || panelParams == null) return true;
                    int dx = (int) (event.getRawX() - startX);
                    int dy = (int) (event.getRawY() - startY);
                    moved = moved || Math.abs(dx) > Ui.dp(OverlayService.this, 4)
                            || Math.abs(dy) > Ui.dp(OverlayService.this, 4);
                    panelParams.x = initialX + dx;
                    panelParams.y = initialY + dy;
                    clampPanelPosition(panelParams.width, panelParams.height);
                    updateWindowLayoutSafely();
                    return true;
                }
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (moved) saveOverlayPositionState();
                    // O header continua arrastável e não gera cliques fantasmas
                    // depois de um movimento real.
                    return true;
                }
                return true;
            }
        };
    }

}
