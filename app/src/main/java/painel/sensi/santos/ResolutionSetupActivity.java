package painel.sensi.santos;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Tela exibida depois da key válida e antes da overlay.
 * A resolução fica fora do painel flutuante para evitar que a construção da
 * overlay seja interrompida por controles de display ou callbacks de wm.
 */
public class ResolutionSetupActivity extends Activity {
    private static final int REQ_NOTIFICATIONS = 7401;
    private SharedPreferences prefs;
    private TextView status;
    private TextView shizukuStatus;
    private android.widget.Switch openPanelSwitch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("santos_session", MODE_PRIVATE);
        getWindow().setStatusBarColor(Ui.INK);
        getWindow().setNavigationBarColor(Ui.INK);
        showScreen();
    }

    private LinearLayout content() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 22), Ui.dp(this, 42), Ui.dp(this, 22), Ui.dp(this, 28));
        root.setBackgroundColor(Ui.INK);
        return root;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = Ui.text(this, value, size, color, bold);
        view.setLineSpacing(Ui.dp(this, 3), 1.05f);
        return view;
    }

    private LinearLayout.LayoutParams height(int dp) {
        return new LinearLayout.LayoutParams(-1, Ui.dp(this, dp));
    }

    private void showScreen() {
        LinearLayout body = content();
        body.addView(text("CONFIGURAÇÃO INICIAL", 12, Ui.BRIGHT, true), height(28));
        body.addView(text("Escolha a resolução antes de abrir o painel", 27, Ui.WHITE, true), height(76));
        body.addView(text("Esta etapa fica separada da bolha flutuante para reduzir travamentos. A primeira opção é abrir o painel; as demais controlam apenas o display.", 15, Ui.MUTED, false), height(82));

        LinearLayout launchCard = new LinearLayout(this);
        launchCard.setGravity(Gravity.CENTER_VERTICAL);
        launchCard.setPadding(Ui.dp(this, 18), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        launchCard.setBackground(Ui.outline(0xFF241039, 0xFF9B4EE2, 18, this));
        LinearLayout launchCopy = new LinearLayout(this);
        launchCopy.setOrientation(LinearLayout.VERTICAL);
        TextView launchTitle = text("ABRIR PAINEL", 17, Ui.WHITE, true);
        TextView launchHint = text("Verificar Shizuku e ativar overlay", 10, Ui.MUTED, false);
        launchCopy.addView(launchTitle, height(25));
        launchCopy.addView(launchHint, height(20));
        launchCard.addView(launchCopy, new LinearLayout.LayoutParams(0, Ui.dp(this, 49), 1));
        openPanelSwitch = new android.widget.Switch(this);
        openPanelSwitch.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.BRIGHT));
        openPanelSwitch.setTrackTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFF9B4EE2, 0xFF51465A}));
        openPanelSwitch.setThumbTintList(new android.content.res.ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{0xFFFFFFFF, 0xFFBEB2C5}));
        openPanelSwitch.setContentDescription("Abrir painel e verificar Shizuku");
        launchCard.addView(openPanelSwitch, new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 52)));
        View.OnClickListener launch = v -> openPanel();
        launchCard.setOnClickListener(launch);
        openPanelSwitch.setOnClickListener(launch);
        body.addView(launchCard, height(70));
        Ui.animatePress(launchCard);
        shizukuStatus = text("Shizuku: aguardando verificação", 11, Ui.MUTED, false);
        body.addView(shizukuStatus, height(38));

        status = text("Nenhuma alteração aplicada nesta sessão.", 12, Ui.MUTED, false);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 8));
        FrameLayout pages = new FrameLayout(this);
        LinearLayout home = new LinearLayout(this); home.setOrientation(LinearLayout.VERTICAL);
        home.addView(text("Santos Team", 12, Ui.BRIGHT, true), height(28));
        home.addView(text("A key libera o app. Use ABRIR PAINEL para verificar o Shizuku e ativar a bolha flutuante.", 15, Ui.MUTED, false), height(92));
        home.addView(text("As alterações de display ficam isoladas na aba Resolução para evitar travamentos e deixar a tela inicial limpa.", 13, Ui.MUTED, false), height(70));
        LinearLayout resolution = new LinearLayout(this); resolution.setOrientation(LinearLayout.VERTICAL);
        resolution.addView(text("RESOLUÇÃO / DPI", 11, Ui.BRIGHT, true), height(34));
        addResolutionControls(resolution);
        addAction(resolution, "Padrão / restaurar display", v -> applyPreset(DisplayManagerHelper.Preset.DEFAULT, 1f));
        addAction(resolution, "Tela cheia (resolução nativa)", v -> applyPreset(DisplayManagerHelper.Preset.FULLSCREEN, 1f));
        addAction(resolution, "Esticado 90%", v -> applyPreset(DisplayManagerHelper.Preset.STRETCHED, .90f));
        addAction(resolution, "Esticado 80%", v -> applyPreset(DisplayManagerHelper.Preset.STRETCHED, .80f));
        resolution.addView(text("PRESETS FLAGSHIP", 11, Ui.BRIGHT, true), height(34));
        addFlagshipActions(resolution);
        resolution.addView(status, height(70));
        LinearLayout compatibility = new LinearLayout(this); compatibility.setOrientation(LinearLayout.VERTICAL);
        compatibility.addView(text("COMPATIBILIDADE", 11, Ui.BRIGHT, true), height(34));
        compatibility.addView(text("Refresh rate é exibido conforme as APIs reais do aparelho. 60/90/120/144 Hz ficam selecionáveis somente quando o DisplayManager confirmar o modo.", 14, Ui.MUTED, false), height(108));
        addAction(compatibility, "LER MODOS DE REFRESH DO APARELHO", v -> readDisplayInfo());

        LinearLayout services = new LinearLayout(this); services.setOrientation(LinearLayout.VERTICAL);
        services.addView(text("SERVIÇOS E RENDERER", 11, Ui.BRIGHT, true), height(34));
        TextView serviceStatus = text("Shizuku: aguardando · Brevent: não verificado", 12, Ui.MUTED, false);
        services.addView(serviceStatus, height(42));
        addAction(services, "VERIFICAR / AUTORIZAR SHIZUKU", v -> {
            serviceStatus.setText("Shizuku: verificando…");
            ShizukuBridge.refreshStatusAsync(this, (available, permission, current) -> {
                if (available && permission) serviceStatus.setText("Shizuku: autorizado");
                else if (available) { serviceStatus.setText("Shizuku: autorização pendente"); ShizukuBridge.requestPermission(); }
                else serviceStatus.setText("Shizuku: serviço não iniciado");
            });
        });
        addAction(services, "ABRIR BREVENT", v -> {
            try {
                Intent breventLaunch = getPackageManager().getLaunchIntentForPackage("me.piebridge.brevent");
                if (breventLaunch == null) serviceStatus.setText("Brevent: não instalado ou sem launcher");
                else { breventLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(breventLaunch); serviceStatus.setText("Brevent: aberto · conclua a ativação nele"); }
            } catch (Throwable error) { serviceStatus.setText("Brevent: não foi possível abrir: " + error.getClass().getSimpleName()); }
        });
        addAction(services, "ATIVAR MODO DESKTOP / DEX", v -> {
            serviceStatus.setText("DeX: verificando Shizuku e capacidades…");
            ShellManager.startDesktopMode(this, (ok, msg) -> runOnUiThread(() -> { serviceStatus.setText("DeX: " + msg); Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }));
        });
        addAction(services, "DESATIVAR MODO DESKTOP / DEX", v -> ShellManager.stopDesktopMode(this, (ok, msg) -> runOnUiThread(() -> { serviceStatus.setText("DeX: " + msg); Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); })));
        services.addView(text("RENDERER DO JOGO", 11, Ui.BRIGHT, true), height(34));
        services.addView(text("As opções ficam no app, mas a troca de renderer de outro jogo só será aplicada quando o Android/ROM expuser uma API confirmável. Não será feita escrita global fictícia que possa travar o aparelho.", 12, Ui.MUTED, false), height(95));
        LinearLayout rendererRow = new LinearLayout(this); rendererRow.setOrientation(LinearLayout.HORIZONTAL);
        for (String renderer : new String[]{"AUTOMÁTICO", "OPENGL", "VULKAN"}) {
            TextView option = Ui.text(this, renderer, 9, Ui.MUTED, true); option.setGravity(Gravity.CENTER); option.setBackground(Ui.outline(Ui.SURFACE, Ui.PURPLE, 8, this));
            option.setOnClickListener(v -> serviceStatus.setText("Renderer " + renderer + ": API externa não confirmada neste aparelho"));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1); rp.rightMargin = Ui.dp(this, 5); rendererRow.addView(option, rp);
        }
        services.addView(rendererRow, height(42));
        services.addView(text("O renderer continua fora do painel flutuante; aqui ele pode ser consultado sem misturar as funções de desempenho.", 11, Ui.MUTED, false), height(48));
        LinearLayout games = new LinearLayout(this); games.setOrientation(LinearLayout.VERTICAL);
        games.addView(text("JOGOS / COMPILER", 11, Ui.BRIGHT, true), height(34));
        games.addView(text("Selecione o package do jogo e aplique somente os perfis oficiais do Android. O resultado real aparece no status.", 13, Ui.MUTED, false), height(74));
        EditText packageInput = new EditText(this);
        packageInput.setSingleLine(true); packageInput.setHint("com.exemplo.jogo");
        packageInput.setText(prefs.getString("selected_game_package", ""));
        packageInput.setTextColor(Ui.WHITE); packageInput.setHintTextColor(Ui.MUTED); packageInput.setTextSize(14);
        packageInput.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        packageInput.setBackground(Ui.outline(Ui.SURFACE, Ui.BRIGHT, 10, this));
        games.addView(packageInput, height(52));
        addAction(games, "SALVAR JOGO SELECIONADO", v -> {
            String pkg = packageInput.getText().toString().trim();
            if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) { packageInput.setError("Package inválido"); return; }
            prefs.edit().putString("selected_game_package", pkg).apply();
            status.setText("Jogo salvo: " + pkg);
        });
        LinearLayout profileRow = new LinearLayout(this); profileRow.setOrientation(LinearLayout.HORIZONTAL);
        for (String profile : new String[]{"SEGURO", "BALANCEADO", "AGRESSIVO"}) {
            TextView option = Ui.text(this, profile, 10, Ui.WHITE, true); option.setGravity(Gravity.CENTER);
            option.setBackground(Ui.outline(Ui.SURFACE, Ui.BRIGHT, 9, this));
            option.setOnClickListener(v -> {
                String pkg = packageInput.getText().toString().trim();
                if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) { packageInput.setError("Informe o package do jogo"); return; }
                prefs.edit().putString("selected_game_package", pkg).apply();
                String mode = profile.equals("AGRESSIVO") ? "speed" : "speed-profile";
                status.setText("Aplicando perfil " + profile + "…");
                ShellManager.compilePackage(pkg, mode, (ok, msg) -> runOnUiThread(() -> { status.setText(profile + ": " + msg); Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }));
            });
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1); pp.rightMargin = Ui.dp(this, 5); profileRow.addView(option, pp);
        }
        games.addView(profileRow, height(48));
        addAction(games, "COMPILAR SPEED", v -> compileGame(packageInput, "speed"));
        addAction(games, "COMPILAR SPEED PROFILE", v -> compileGame(packageInput, "speed-profile"));
        games.addView(text("Os perfis não alteram arquivos do jogo. O Android pode recusar o modo quando o package não estiver instalado ou quando o firmware não expuser o compilador.", 11, Ui.MUTED, false), height(60));

        pages.addView(home, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(games, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(resolution, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(compatibility, new FrameLayout.LayoutParams(-1, -1));
        pages.addView(services, new FrameLayout.LayoutParams(-1, -1));
        for (int hidden = 1; hidden < pages.getChildCount(); hidden++) pages.getChildAt(hidden).setVisibility(View.GONE);
        String[] tabNames = {"INÍCIO", "JOGOS", "RESOLUÇÃO", "COMPAT.", "SERVIÇOS"};
        for (int i = 0; i < tabNames.length; i++) {
            final int index = i;
            TextView tab = Ui.text(this, tabNames[i], 10, i == 0 ? Ui.WHITE : Ui.MUTED, true);
            tab.setGravity(Gravity.CENTER); tab.setBackground(Ui.rounded(i == 0 ? Ui.PURPLE : Ui.SURFACE, 10, this));
            tab.setOnClickListener(v -> {
                for (int j = 0; j < pages.getChildCount(); j++) pages.getChildAt(j).setVisibility(j == index ? View.VISIBLE : View.GONE);
                for (int j = 0; j < tabs.getChildCount(); j++) {
                    TextView t = (TextView) tabs.getChildAt(j); t.setTextColor(j == index ? Ui.WHITE : Ui.MUTED); t.setBackground(Ui.rounded(j == index ? Ui.PURPLE : Ui.SURFACE, 10, this));
                }
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1); tp.rightMargin = Ui.dp(this, 5);
            tabs.addView(tab, tp);
        }
        body.addView(tabs, height(48));
        body.addView(pages, new LinearLayout.LayoutParams(-1, 0, 1));
        body.addView(text("O botão Abrir Painel verifica o Shizuku somente quando tocado; nenhuma ponte é sondada dentro do painel flutuante.", 12, Ui.MUTED, false), height(54));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        setContentView(scroll);
    }

    private void addAction(LinearLayout body, String label, View.OnClickListener listener) {
        TextView action = Ui.text(this, label, 15, Ui.WHITE, true);
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        action.setBackground(Ui.outline(0xFF102234, 0xFF465250, 10, this));
        action.setOnClickListener(listener);
        body.addView(action, height(48));
        LinearLayout.LayoutParams spacer = height(8);
        body.addView(new View(this), spacer);
    }

    private EditText numericInput(String hint, String value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(hint);
        input.setText(value);
        input.setTextColor(Ui.WHITE);
        input.setHintTextColor(Ui.MUTED);
        input.setTextSize(14);
        input.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        input.setBackground(Ui.outline(0xFF102234, 0xFF465250, 9, this));
        return input;
    }

    private void addResolutionControls(LinearLayout body) {
        addAction(body, "LER wm size / wm density AGORA", v -> readDisplayInfo());
        body.addView(text("PERFIL MANUAL · WIDTH / HEIGHT / DPI", 10, Ui.MUTED, true), height(28));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        EditText width = numericInput("Width", "1080");
        EditText heightInput = numericInput("Height", "2400");
        EditText dpi = numericInput("DPI", "0");
        row.addView(width, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
        row.addView(heightInput, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
        row.addView(dpi, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
        body.addView(row, height(50));
        addAction(body, "APLICAR resolução / DPI personalizada", v -> {
            try {
                int w = Integer.parseInt(width.getText().toString().trim());
                int h = Integer.parseInt(heightInput.getText().toString().trim());
                int d = Integer.parseInt(dpi.getText().toString().trim());
                if (w < 320 || h < 320) throw new NumberFormatException();
                applyCustomWithDensity(w, h, Math.max(0, d));
            } catch (NumberFormatException error) {
                status.setText("Informe Width e Height positivos; DPI 0 = automático pelo aparelho");
            }
        });
        addAction(body, "SALVAR PRESET STRETCH RESOLUTION (ORIGINAL)", v -> {
            prefs.edit().putBoolean("resolution_setup_snapshot_saved", true).apply();
            status.setText("Preset de resolução original salvo");
        });
        addAction(body, "RESTAURAR resolução e DPI originais", v -> applyPreset(DisplayManagerHelper.Preset.DEFAULT, 1f));
        addSlider(body, "Alongar tela · Width/X", 100, "Width/X: 0 = padrão · 100 = limite seguro");
        addSlider(body, "Alongar tela · Height/Y", 10, "Height/Y: 0 = padrão · 10 = limite seguro");
    }

    private void addSlider(LinearLayout body, String title, int max, String description) {
        TextView label = text(title + " · 0", 13, Ui.WHITE, true);
        body.addView(label, height(24));
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(0);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                label.setText(title + " · " + progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        body.addView(bar, height(42));
        body.addView(text(description, 10, Ui.MUTED, false), height(28));
    }

    private void readDisplayInfo() {
        try {
            android.graphics.Point point = new android.graphics.Point();
            getWindowManager().getDefaultDisplay().getRealSize(point);
            int dpi = getResources().getDisplayMetrics().densityDpi;
            status.setText("Display físico lido: " + point.x + " x " + point.y + " · " + dpi + " dpi");
        } catch (Throwable error) {
            status.setText("Não foi possível ler o display: " + error.getClass().getSimpleName());
        }
    }

    private void addFlagshipActions(LinearLayout body) {
        String[][] presets = {
                {"ASUS ROG Phone 10 Ultimate", "2400", "1080"}, {"RedMagic 11 Pro", "2688", "1216"},
                {"iPhone 16 Pro Max", "2868", "1320"}, {"Samsung Galaxy S25 Ultra", "3120", "1440"},
                {"iQOO 13", "3168", "1440"}, {"iQOO 15 Pro", "3168", "1440"},
                {"OnePlus 12", "3168", "1440"}, {"OnePlus 15", "2772", "1272"},
                {"Xiaomi 14T / 14T Pro", "2712", "1220"}, {"Black Shark 6 Pro", "2400", "1080"},
                {"Poco X7 Pro 5G", "2712", "1220"}, {"Poco F7 / F7 GT", "2772", "1280"},
                {"Redmi Note 14 Pro 5G", "2712", "1220"}, {"Infinix GT 30 Pro", "2720", "1224"},
                {"Samsung Galaxy A56 5G", "2340", "1080"}, {"Moto G75 5G", "2388", "1080"},
                {"Moto G85 5G", "2400", "1080"}, {"Realme GT 6 / Neo 6", "2780", "1264"}
        };
        for (String[] preset : presets) {
            int width = Integer.parseInt(preset[1]);
            int height = Integer.parseInt(preset[2]);
            addAction(body, preset[0] + " · " + width + " x " + height,
                    v -> applyCustom(width, height));
        }
    }

    private void compileGame(EditText input, String mode) {
        String pkg = input.getText().toString().trim();
        if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) { input.setError("Informe um package válido"); return; }
        prefs.edit().putString("selected_game_package", pkg).apply();
        status.setText("Aplicando " + mode + "…");
        ShellManager.compilePackage(pkg, mode, (ok, msg) -> runOnUiThread(() -> { status.setText(msg); Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }));
    }

    private void applyCustom(int width, int height) {
        applyCustomWithDensity(width, height, 0);
    }

    private void applyCustomWithDensity(int width, int height, int density) {
        status.setText("Aplicando " + width + " x " + height + "…");
        DisplayManagerHelper.applyCustomProfile(this, width, height, density, true,
                (ok, message) -> runOnUiThread(() -> {
                    status.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                }));
    }

    private void applyPreset(DisplayManagerHelper.Preset preset, float ratio) {
        status.setText("Aplicando " + preset.name() + "…");
        DisplayManagerHelper.applyStretchPreset(this, preset, ratio, (ok, message) -> runOnUiThread(() -> {
            status.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }));
    }

    private void openPanel() {
        if (openPanelSwitch != null) {
            openPanelSwitch.setEnabled(false);
            openPanelSwitch.setChecked(false);
        }
        if (shizukuStatus != null) shizukuStatus.setText("Shizuku: verificando…");
        ShizukuBridge.refreshStatusAsync(this, (available, permission, current) -> {
            if (available && permission) {
                if (shizukuStatus != null) shizukuStatus.setText("Shizuku: autorizado · abrindo painel");
                if (openPanelSwitch != null) openPanelSwitch.setChecked(true);
                startPanelAfterShizuku();
            } else if (available) {
                if (shizukuStatus != null) shizukuStatus.setText("Shizuku: autorize o app e toque novamente");
                ShizukuBridge.requestPermission();
                Toast.makeText(this, "Autorize o Santos Team no Shizuku e toque novamente.", Toast.LENGTH_LONG).show();
                if (openPanelSwitch != null) openPanelSwitch.setEnabled(true);
            } else {
                if (shizukuStatus != null) shizukuStatus.setText("Shizuku: não iniciado · abra o Shizuku");
                Toast.makeText(this, "Abra o Shizuku, inicie o serviço e toque em Abrir Painel.", Toast.LENGTH_LONG).show();
                if (openPanelSwitch != null) openPanelSwitch.setEnabled(true);
            }
        });
    }

    private void startPanelAfterShizuku() {
        if (!Settings.canDrawOverlays(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Permissão do painel")
                    .setMessage("Conceda a permissão para o Santos Team aparecer sobre outros apps.")
                    .setPositiveButton("Conceder", (d, w) -> {
                        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()))); }
                        catch (RuntimeException e) { Toast.makeText(this, "Não foi possível abrir a permissão.", Toast.LENGTH_LONG).show(); }
                    })
                    .setNegativeButton("Cancelar", null).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
            return;
        }
        startOverlay();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_NOTIFICATIONS) startOverlay();
    }

    private void startOverlay() {
        Intent intent = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_OPEN)
                .putExtra(MainActivity.EXTRA_LICENSE_KEY, getIntent().getStringExtra(MainActivity.EXTRA_LICENSE_KEY));
        try {
            if (OverlayService.isAlive()) startService(intent);
            else if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            finish();
        } catch (Throwable error) {
            Toast.makeText(this, "Falha ao abrir o painel: " + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }
}
