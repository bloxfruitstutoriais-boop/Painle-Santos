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

        TextView open = Ui.button(this, "ABRIR PAINEL   ▣");
        open.setOnClickListener(v -> openPanel());
        body.addView(open, height(58));
        Ui.animatePress(open);

        body.addView(text("RESOLUÇÃO / DPI", 11, Ui.BRIGHT, true), height(34));
        addResolutionControls(body);
        addAction(body, "Padrão / restaurar display", v -> applyPreset(DisplayManagerHelper.Preset.DEFAULT, 1f));
        addAction(body, "Tela cheia (resolução nativa)", v -> applyPreset(DisplayManagerHelper.Preset.FULLSCREEN, 1f));
        addAction(body, "Esticado 90%", v -> applyPreset(DisplayManagerHelper.Preset.STRETCHED, .90f));
        addAction(body, "Esticado 80%", v -> applyPreset(DisplayManagerHelper.Preset.STRETCHED, .80f));

        body.addView(text("PRESETS FLAGSHIP", 11, Ui.BRIGHT, true), height(34));
        addFlagshipActions(body);
        status = text("Nenhuma alteração aplicada nesta sessão.", 12, Ui.MUTED, false);
        body.addView(status, height(58));
        body.addView(text("A aplicação exige Shizuku ou WRITE_SECURE_SETTINGS. Se preferir, abra o painel e configure depois nesta tela.", 12, Ui.MUTED, false), height(58));

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
                status.setText("Informe Width, Height e DPI válidos");
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
        if (!Settings.canDrawOverlays(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Permissão do painel")
                    .setMessage("Conceda a permissão para o PAINEL SANTOS aparecer sobre outros apps.")
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
