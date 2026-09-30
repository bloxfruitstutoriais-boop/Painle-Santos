package painel.sensi.santos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restaura a bolha após o usuário ocultá-la durante uma transmissão. */
public final class OverlayControlReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !OverlayService.ACTION_SHOW.equals(intent.getAction())) return;
        Intent service = new Intent(context, OverlayService.class)
                .setAction(OverlayService.ACTION_SHOW);
        try {
            // Reutilizar a instância viva evita um segundo startForegroundService;
            // se ela já terminou, uma nova execução cria o serviço novamente.
            if (OverlayService.isAlive()) {
                context.startService(service);
            } else if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable error) {
            // CORRIGIDO: registrar falha de inicialização do serviço sem deixar
            // exceção escapar do BroadcastReceiver.
            android.util.Log.e("OverlayControlReceiver", "Falha ao mostrar overlay", error);
        }
    }
}
