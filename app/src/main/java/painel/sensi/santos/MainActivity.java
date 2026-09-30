package painel.sensi.santos;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;

public class MainActivity extends android.app.Activity {
    // ADICIONADO: ação usada pelo X da overlay para abrir a tela de key do zero.
    public static final String ACTION_KEY_SCREEN = "painel.sensi.santos.KEY_SCREEN"; // CORRIGIDO BUG2: mantém a ação existente da tela de key.
    public static final String EXTRA_LICENSE_KEY = "license_key"; // CORRIGIDO BUG2: entrega a key apenas à sessão ativa do serviço para Revalidar key.
    private SharedPreferences prefs;
    private final Handler handler = new Handler();
    private boolean waitingForOverlay;
    private boolean pendingFloatingLaunch;
    private boolean notificationPermissionRequestInProgress;
    private boolean overlayLaunchScheduled;
    private long overlayRequestId;
    // ADICIONADO: impede o splash de validar automaticamente ao voltar do X.
    private boolean forceKeyScreen; // CORRIGIDO BUG2: mantém o fluxo original da tela de key.
    private String activeLicenseKey = ""; // CORRIGIDO BUG2: permite revalidar sem persistir a key quando o usuário desmarcou Salvar key.
    // LOG ADICIONADO: request/callback são cancelados no lifecycle da Activity.
    private LicenseValidator.Request licenseRequest;
    private LicenseValidator.Callback licenseCallback;
    private static final int REQ_NOTIFICATIONS = 6401;
    private static final String FLOW_TAG = "DEBUG_FLOW";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Log.d(FLOW_TAG, "MAIN_ON_CREATE"); // LOG ADICIONADO
        Window w = getWindow();
        w.setStatusBarColor(Ui.INK);
        w.setNavigationBarColor(Ui.INK);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.INK));
        prefs = getSharedPreferences("santos_session", MODE_PRIVATE);
        // CORRIGIDO: reconhecer a solicitação da overlay para exibir a key sem auto-login.
        forceKeyScreen = intentHasKeyScreen(getIntent());
        // O OverlayService pode continuar vivo enquanto a Activity é recriada.
        // Não o mate aqui: a abertura abaixo envia ACTION_OPEN e reutiliza a
        // mesma instância, evitando a corrida stopService() -> startForegroundService().
        LicenseValidator.stopMonitoring();
        showSplash();
    }

    @Override protected void onResume() {
        super.onResume();
        Log.d(FLOW_TAG, "MAIN_ON_RESUME overlayWaiting=" + waitingForOverlay); // LOG ADICIONADO
        try {
            if (waitingForOverlay && Settings.canDrawOverlays(this)) {
                waitingForOverlay = false; continuePermissionFlow();
            }
        } catch (RuntimeException error) {
            Log.e(FLOW_TAG, "MAIN_OVERLAY_PERMISSION_CHECK_FAILED", error); // TRY/CATCH ADICIONADO
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            notificationPermissionRequestInProgress = false;
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.d(FLOW_TAG, "MAIN_NOTIFICATION_RESULT granted=" + granted); // LOG ADICIONADO
            if (!granted) {
                Toast.makeText(this, "Notificações negadas; o painel continuará sem alertas visuais.",
                        Toast.LENGTH_SHORT).show();
            }
            if (pendingFloatingLaunch) continuePermissionFlowAfterNotification();
        }
    }

    @Override protected void onDestroy() {
        Log.d(FLOW_TAG, "MAIN_ON_DESTROY"); // LOG ADICIONADO
        handler.removeCallbacksAndMessages(null);
        cancelLicenseRequest();
        // CORRIGIDO: não manter a coroutine de licença quando a Activity foi
        // abandonada antes de qualquer overlay ficar pronta. Após launchOverlay
        // o requestId protege o monitor necessário à sessão do serviço.
        if (prefs != null && overlayRequestId <= 0L
                && !prefs.getBoolean("overlay_ready", false)) {
            LicenseValidator.stopMonitoring();
        }
        super.onDestroy();
    }

    private void cancelLicenseRequest() {
        if (licenseRequest != null) {
            licenseRequest.cancel();
            licenseRequest = null;
        }
        licenseCallback = null;
    }

    private boolean uiAlive() {
        return !isFinishing() && !isDestroyed();
    }

    private FrameLayout root() { FrameLayout r = new FrameLayout(this); Ui.addBackdrop(this, r); return r; }
    private void setContentViewAnimated(View v) {
        /*
         * Não anime a raiz de alpha 0: durante a troca de setContentView() o
         * Android expõe o fundo da janela por um frame e a transição vira uma
         * tela cinza em alguns aparelhos. A raiz permanece opaca e só desliza
         * alguns dp, mantendo o wallpaper/tint visíveis durante toda a troca.
         */
        v.setAlpha(1f);
        v.setTranslationY(Ui.dp(this, 16));
        setContentView(v);
        v.animate().translationY(0f).setDuration(300).start();
    }
    private FrameLayout.LayoutParams lp(int w, int h) { return new FrameLayout.LayoutParams(w, h); }
    private FrameLayout.LayoutParams lp(int w, int h, int gravity) { FrameLayout.LayoutParams p = lp(w,h); p.gravity=gravity; return p; }
    private FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = lp(w,h,gravity); p.setMargins(Ui.dp(this,l),Ui.dp(this,t),Ui.dp(this,r),Ui.dp(this,b)); return p;
    }
    private LinearLayout.LayoutParams vlp(int w, int h) { return new LinearLayout.LayoutParams(w,h); }
    private LinearLayout.LayoutParams vlp(int w, int h, int weight) { return new LinearLayout.LayoutParams(w,h,weight); }

    private void showSplash() {
        Log.d(FLOW_TAG, "MAIN_SPLASH_SHOW"); // LOG ADICIONADO
        // Splash minimalista: somente uma bolinha carregando, sem imagem.
        FrameLayout cover = new FrameLayout(this);
        cover.setBackgroundColor(0xFF111111);
        ProgressBar loading = new ProgressBar(this);
        loading.setIndeterminate(true);
        loading.setContentDescription("Carregando");
        loading.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Ui.BRIGHT));
        cover.addView(loading, lp(Ui.dp(this, 42), Ui.dp(this, 42), Gravity.CENTER));
        setContentView(cover);
        handler.postDelayed(() -> {
            // O X sempre volta para a tela de inserção, sem iniciar a overlay.
            if (forceKeyScreen) {
                Log.d(FLOW_TAG, "MAIN_SPLASH_TO_KEY_FORCED"); // LOG ADICIONADO
                stopService(new Intent(this, OverlayService.class));
                showKey();
                return;
            }
            boolean saveKey = prefs.getBoolean("save_key", false);
            String saved = saveKey ? prefs.getString("key", "") : "";
            if (saved == null) saved = "";
            saved = saved.trim();
            if (!saved.isEmpty()) {
                Log.d(FLOW_TAG, "MAIN_SPLASH_TO_KEY_VERIFY_SAVED"); // LOG ADICIONADO
                verifySavedKey(saved);
            } else {
                Log.d(FLOW_TAG, "MAIN_SPLASH_TO_KEY_NO_SAVED_KEY"); // LOG ADICIONADO
                // Não existe licença para manter uma overlay antiga aberta.
                stopService(new Intent(this, OverlayService.class));
                // O onboarding de quatro páginas foi removido. Depois da capa
                // há somente a tela de key; Shizuku continua opcional.
                showKey();
            }
        }, 350L);
    }

    private void verifySavedKey(final String key) {
        activeLicenseKey = key == null ? "" : key.trim(); // CORRIGIDO BUG2: registra a key somente durante a sessão corrente.
        cancelLicenseRequest();
        if (LicenseValidator.hasValidLocalSession(this, key)) {
            // Sessão local ainda válida: falha de rede não impede a abertura.
            Log.d(FLOW_TAG, "MAIN_KEY_LOCAL_SESSION_VALID");
            LicenseValidator.startMonitoring(getApplicationContext(), key);
            openResolutionSetup();
            return;
        }
        Log.d(FLOW_TAG, "MAIN_KEY_VERIFY_SAVED_START"); // LOG ADICIONADO
        licenseCallback = new LicenseValidator.Callback() {
            @Override public void onResult(LicenseValidator.Result result) {
                licenseRequest = null;
                licenseCallback = null;
                if (!uiAlive()) return;
                Log.d(FLOW_TAG, "MAIN_KEY_VERIFY_SAVED_RESULT valid=" + result.valid); // LOG ADICIONADO
                if (result.valid) {
                    LicenseValidator.startMonitoring(getApplicationContext(), key);
                    openResolutionSetup();
                } else {
                    Log.d(FLOW_TAG, "MAIN_KEY_VERIFY_SAVED_REJECTED definitive="
                            + result.definitiveInvalid);
                    stopService(new Intent(MainActivity.this, OverlayService.class));
                    // Somente uma resposta explícita de inválida/expirada/revogada
                    // remove a key salva. Falha transitória preserva a sessão/key.
                    if (result.definitiveInvalid) {
                        prefs.edit().remove("key").putBoolean("save_key", false).apply();
                        LicenseValidator.clearLocalSession(MainActivity.this);
                    }
                    Toast.makeText(MainActivity.this,
                            result.message, Toast.LENGTH_LONG).show();
                    showKey();
                }
            }
        };
        licenseRequest = LicenseValidator.verifyOnce(this, key, licenseCallback);
    }

    private void showKey() {
        FrameLayout r=root();
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(Ui.dp(this,22),Ui.dp(this,65),Ui.dp(this,22),Ui.dp(this,34));
        TextView keyIcon=Ui.text(this,"⚿",46,Ui.BRIGHT,true); body.addView(keyIcon,vlp(-1,Ui.dp(this,64))); 
        TextView title=Ui.text(this,"Acesso protegido",34,Ui.WHITE,true); body.addView(title,vlp(-1,Ui.dp(this,58)));
        TextView desc=Ui.text(this,"Cole sua key de acesso para liberar o aplicativo e abrir o painel flutuante.",18,Ui.MUTED,false); desc.setLineSpacing(Ui.dp(this,4),1.08f); LinearLayout.LayoutParams dp=vlp(-1,Ui.dp(this,62)); dp.bottomMargin=Ui.dp(this,34); body.addView(desc,dp);
        android.widget.EditText input=new android.widget.EditText(this); input.setSingleLine(true);
        // CORRIGIDO: só preencher automaticamente quando o usuário escolheu salvar a key.
        String rememberedKey = prefs.getBoolean("save_key", false)
                ? prefs.getString("key", "") : "";
        input.setText(rememberedKey == null ? "" : rememberedKey); input.setHint("Key de acesso"); input.setHintTextColor(0xFFBEB3C6); input.setTextColor(Ui.WHITE); input.setTextSize(18); input.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0); input.setBackground(Ui.outline(0x331A1326,0xFF9D8DA7,12,this)); body.addView(input,vlp(-1,Ui.dp(this,62)));
        // ADICIONADO: opção explícita para persistir ou não a key neste aparelho.
        android.widget.CheckBox saveKey = new android.widget.CheckBox(this);
        saveKey.setText("Salvar key neste aparelho");
        saveKey.setTextColor(Ui.MUTED);
        saveKey.setTextSize(14);
        saveKey.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.BRIGHT));
        saveKey.setChecked(prefs.getBoolean("save_key", false));
        LinearLayout.LayoutParams saveKeyParams = vlp(-1, Ui.dp(this, 48));
        saveKeyParams.topMargin = Ui.dp(this, 8);
        body.addView(saveKey, saveKeyParams);
        TextView activate=Ui.button(this,"Ativar painel   ▣"); LinearLayout.LayoutParams ap=vlp(-1,Ui.dp(this,60)); ap.topMargin=Ui.dp(this,26); body.addView(activate,ap);
        TextView note=Ui.text(this,"A key é verificada online antes de abrir o painel; a sessão válida é preservada e revalidada periodicamente.",14,Ui.MUTED,false); note.setLineSpacing(Ui.dp(this,4),1.05f); LinearLayout.LayoutParams np=vlp(-1,Ui.dp(this,72)); np.topMargin=Ui.dp(this,24); body.addView(note,np);
        activate.setOnClickListener(v->{
            Log.d(FLOW_TAG, "MAIN_KEY_ACTIVATE_CLICK"); // LOG ADICIONADO
            String k=input.getText().toString().trim();
            if(k.isEmpty()){input.setError("Digite uma key");return;}
            activate.setEnabled(false);
            activate.setText("Verificando...");
            cancelLicenseRequest();
            licenseCallback = new LicenseValidator.Callback() {
                @Override public void onResult(LicenseValidator.Result result) {
                    licenseRequest = null;
                    licenseCallback = null;
                    if (!uiAlive()) return;
                    Log.d(FLOW_TAG, "MAIN_KEY_ACTIVATE_RESULT valid=" + result.valid); // LOG ADICIONADO
                    activate.setEnabled(true);
                    activate.setText("Ativar painel   ▣");
                    if (result.valid) {
                        activeLicenseKey = k; // CORRIGIDO BUG2: disponibiliza a key ao serviço sem depender do cache antigo.
                        // CORRIGIDO: persistir somente com consentimento explícito da caixa.
                        boolean shouldSave = saveKey.isChecked();
                        SharedPreferences.Editor keyEditor = prefs.edit()
                                .putBoolean("save_key", shouldSave);
                        if (shouldSave) keyEditor.putString("key", k);
                        else keyEditor.remove("key");
                        keyEditor.apply();
                        LicenseValidator.startMonitoring(getApplicationContext(), k);
                        openResolutionSetup();
                    } else {
                        input.setError(result.message);
                        Toast.makeText(MainActivity.this, result.message, Toast.LENGTH_LONG).show();
                    }
                }
            };
            activeLicenseKey = k; // CORRIGIDO BUG2: guarda a key apenas em memória para a sessão ativa.
            licenseRequest = LicenseValidator.verifyOnce(this, k, licenseCallback);
        });
        Ui.animatePress(activate);
        scroll.addView(body); r.addView(scroll,lp(-1,-1)); setContentViewAnimated(r);
    }

    private void openResolutionSetup() {
        Log.d(FLOW_TAG, "MAIN_RESOLUTION_SETUP_OPEN");
        Intent intent = new Intent(this, ResolutionSetupActivity.class)
                .putExtra(EXTRA_LICENSE_KEY, activeLicenseKey);
        startActivity(intent);
        finish();
    }

    private void continuePermissionFlow() {
        Log.d(FLOW_TAG, "MAIN_PERMISSION_FLOW"); // LOG ADICIONADO
        if (!pendingFloatingLaunch || overlayLaunchScheduled) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            if (!notificationPermissionRequestInProgress) {
                notificationPermissionRequestInProgress = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            }
            return;
        }
        continuePermissionFlowAfterNotification();
    }

    private void continuePermissionFlowAfterNotification() {
        Log.d(FLOW_TAG, "MAIN_PERMISSION_FLOW_AFTER_NOTIFICATION"); // LOG ADICIONADO
        if (!pendingFloatingLaunch || overlayLaunchScheduled) return;
        // CORRIGIDO: não tocar na API/provider do Shizuku durante a abertura.
        // O painel deve abrir mesmo com Shizuku parado, quebrado ou ausente;
        // a ponte só é acessada pelo botão Autorizar/Testar dentro da overlay.
        overlayLaunchScheduled = true;
        handler.postDelayed(this::launchOverlay, 650);
    }

    private void launchOverlay() {
        Log.d(FLOW_TAG, "MAIN_OVERLAY_LAUNCH_SERVICE"); // LOG ADICIONADO
        if (!uiAlive()) return;
        overlayLaunchScheduled = false;
        pendingFloatingLaunch = false;
        final long requestId = Math.max(1L, SystemClock.elapsedRealtime());
        overlayRequestId = requestId;
        prefs.edit().putBoolean("overlay_ready", false)
                .putLong("overlay_request_id", requestId)
                .remove("overlay_error").apply();
        Intent i = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_OPEN)
                .putExtra(OverlayService.EXTRA_REQUEST_ID, requestId) // CORRIGIDO BUG2: mantém o identificador usado pelo fluxo de abertura.
                .putExtra(EXTRA_LICENSE_KEY, activeLicenseKey); // CORRIGIDO BUG2: permite o botão Revalidar key sem alterar a opção Salvar key.
        try {
            // CORRIGIDO: se o serviço já existe, enviar startService reutiliza a
            // instância foreground; iniciar outro FGS na reabertura podia causar
            // estado concorrente/ForegroundServiceStartNotAllowedException.
            if (OverlayService.isAlive()) {
                startService(i);
            } else if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            // CORRIGIDO: esperar alguns ciclos pelo addView real, em vez de
            // concluir que a overlay falhou após apenas 1,1 s.
            handler.postDelayed(() -> awaitOverlayReady(requestId, 0), 150);
        } catch (Throwable error) {
            Log.e(FLOW_TAG, "MAIN_OVERLAY_START_FAILED", error); // TRY/CATCH ADICIONADO
            // CORRIGIDO: registrar o tipo real e manter a key para retry.
            prefs.edit().putBoolean("overlay_ready", false)
                    .putString("overlay_error", error.getClass().getSimpleName() + ": "
                            + (error.getMessage() == null ? "sem mensagem" : error.getMessage())).apply();
            LicenseValidator.stopMonitoring();
            Toast.makeText(this, "Falha ao iniciar o painel: "
                    + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            showKey();
        }
    }

    // ADICIONADO: confirmação com polling curto evita falso erro enquanto o
    // Android ainda está anexando a janela ou criando a instância do serviço.
    private void awaitOverlayReady(final long requestId, final int attempt) {
        if (!uiAlive() || overlayRequestId != requestId) return;
        boolean ready = prefs.getBoolean("overlay_ready", false)
                && prefs.getLong("overlay_ready_request_id", -1L) == requestId;
        if (ready) {
            Log.d(FLOW_TAG, "MAIN_OVERLAY_READY"); // LOG ADICIONADO
            // CORRIGIDO: somente fechar a Activity depois do addView confirmado.
            finish();
            return;
        }
        if (attempt < 20) {
            // ADICIONADO: até 3 segundos para o serviço responder sem bloquear a UI.
            handler.postDelayed(() -> awaitOverlayReady(requestId, attempt + 1), 150);
            return;
        }
        String error = prefs.getString("overlay_error", "serviço não confirmou a janela");
        Log.e(FLOW_TAG, "MAIN_OVERLAY_NOT_READY " + error); // LOG ADICIONADO
        // CORRIGIDO: manter a key salva e permitir nova tentativa; apagar a key
        // aqui mascarava a falha da overlay como problema de licença.
        LicenseValidator.stopMonitoring();
        Toast.makeText(this, "Não foi possível abrir o painel (" + error
                + "). Verifique overlay/foreground service e tente novamente.", Toast.LENGTH_LONG).show();
        showKey();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // CORRIGIDO: reabrir com a ação do X cancela callbacks antigos e mostra a key.
        setIntent(intent);
        if (intentHasKeyScreen(intent)) {
            forceKeyScreen = true;
            handler.removeCallbacksAndMessages(null);
            pendingFloatingLaunch = false;
            overlayLaunchScheduled = false;
            cancelLicenseRequest();
            LicenseValidator.stopMonitoring();
            showKey();
        }
    }

    private boolean intentHasKeyScreen(Intent intent) {
        // ADICIONADO: comparação isolada evita NullPointerException em intents externos.
        return intent != null && ACTION_KEY_SCREEN.equals(intent.getAction());
    }

}
