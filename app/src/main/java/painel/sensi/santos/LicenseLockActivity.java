package painel.sensi.santos;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Tela terminal exibida quando a licença deixa de ser válida. */
public final class LicenseLockActivity extends Activity {
    private static final String EXTRA_REASON = "reason";

    public static void open(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        // Falha fechada mesmo se o Android bloquear uma Activity iniciada em
        // background: a bolha é removida antes da tentativa de navegação.
        LicenseValidator.stopMonitoring();
        appContext.getSharedPreferences("santos_session", Context.MODE_PRIVATE)
                .edit().remove("key").apply();
        LicenseValidator.clearLocalSession(appContext);
        appContext.stopService(new Intent(appContext, OverlayService.class));
        Intent intent = new Intent(appContext, LicenseLockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_REASON, reason == null ? "" : reason);
        try {
            appContext.startActivity(intent);
        } catch (RuntimeException error) {
            // O serviço já foi encerrado; registre a falha sem incluir key,
            // device id ou a razão retornada pela API.
            Log.e("LicenseLockActivity", "Não foi possível abrir a tela de bloqueio", error);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LicenseValidator.stopMonitoring();
        getSharedPreferences("santos_session", MODE_PRIVATE).edit().remove("key").apply();
        LicenseValidator.clearLocalSession(this);
        stopService(new Intent(this, OverlayService.class));
        getWindow().setStatusBarColor(Ui.INK);
        getWindow().setNavigationBarColor(Ui.INK);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.INK);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), 0);

        TextView mark = Ui.text(this, "SANTOS/", 14, Ui.BRIGHT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setLetterSpacing(.22f);
        content.addView(mark, new LinearLayout.LayoutParams(-1, Ui.dp(this, 32)));

        TextView title = Ui.text(this, "Acesso bloqueado", 31, Ui.WHITE, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 54));
        titleParams.topMargin = Ui.dp(this, 18);
        content.addView(title, titleParams);

        TextView description = Ui.text(this,
                "A licença não pôde ser confirmada pela API.\n\n"
                        + "O painel foi encerrado por segurança. Verifique sua key e sua conexão "
                        + "antes de tentar novamente.",
                17, Ui.MUTED, false);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(Ui.dp(this, 5), 1.08f);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 150));
        descriptionParams.topMargin = Ui.dp(this, 12);
        content.addView(description, descriptionParams);

        TextView close = Ui.button(this, "Fechar aplicativo");
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, Ui.dp(this, 60));
        closeParams.topMargin = Ui.dp(this, 24);
        content.addView(close, closeParams);
        close.setOnClickListener(v -> finishAndRemoveTask());
        Ui.animatePress(close);

        FrameLayout.LayoutParams contentParams = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        root.addView(content, contentParams);
        setContentView(root);
    }

    @Override public void onBackPressed() {
        finishAndRemoveTask();
    }
}
