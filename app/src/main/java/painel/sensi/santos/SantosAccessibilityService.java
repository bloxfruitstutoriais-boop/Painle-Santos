package painel.sensi.santos;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Camada mínima de interação para a sessão desktop. Não lê, salva ou envia
 * conteúdo de tela; apenas mantém o serviço disponível para a sessão criada
 * pelo display virtual.
 */
public final class SantosAccessibilityService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // A sessão não precisa processar texto ou conteúdo de outros apps.
    }
    @Override public void onInterrupt() { }
}
