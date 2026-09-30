# Patch — correção da abertura, bolha e painel

## Correções aplicadas

- `MainActivity.java`
  - Ao iniciar novamente, cancela o monitor de licença anterior e encerra uma instância antiga do `OverlayService` antes de validar a key.
  - Isso evita duas coroutines de licenciamento ou duas janelas de overlay concorrentes quando o app é fechado e aberto novamente.
  - O conteúdo agora ocupa a área entre o cabeçalho e o rodapé.
  - O rodapé foi ajustado para não cobrir o último conteúdo.
  - Callbacks assíncronos não atualizam uma Activity já destruída.
  - Callbacks pendentes do `Handler` são removidos em `onDestroy`.
  - A abertura usa `request_id` e não aceita o `overlay_ready` deixado por uma instância anterior.
  - A leitura da key salva tolera valor nulo.
  - Texto da etapa de key atualizado para informar a validação online.

- `FeatureIconView.java`
  - Corrigidos os limites dos arcos do ícone de impressão digital; os valores anteriores passavam coordenadas menores que o centro e podiam deixar o desenho invisível.

- `OverlayService.java`
  - O `startForeground()` agora usa `ic_stat_santos.xml`, um ícone vector monocromático válido para notificação. O PNG RGB da capa não é mais usado como small icon, evitando `Bad notification for startForeground` em aparelhos que rejeitam ícones coloridos.
  - A bolha agora usa exclusivamente `res/drawable/floating_button.png`, a imagem da arma fornecida, em `CENTER_CROP` e sem padding que reduza/oculte o desenho.
  - O painel expandido foi reduzido para **250dp × no máximo 360dp**, com rolagem interna.
  - A atualização periódica do status do Shizuku também recalcula os estados dos controles; a autorização passa a liberar as opções sem reiniciar a Activity.

- `MainActivity.java`
  - O fundo da janela é fixado em `Ui.INK` e as trocas de onboarding/key não deixam a raiz transparente; isso elimina o frame cinza entre as telas 1–4.

## Ocultar durante transmissão

- A aba **OTIMIZAÇÃO** recebeu o switch **Ocultar na transmissão**.
- Ao ativar, o `OverlayService` remove a janela do `WindowManager`; a bolha e o painel deixam de aparecer na tela e na captura/compartilhamento.
- O serviço foreground permanece vivo para manter o estado, mas a notificação muda para **Mostrar painel**.
- Tocar em **Mostrar painel** restaura a bolha recolhida através de `OverlayControlReceiver`.
- O modo não depende de detectar automaticamente qual app iniciou a transmissão e não aplica `FLAG_SECURE` ao restante do aparelho; ele oculta somente o painel.

## Limitação de verificação


O código foi verificado estaticamente, mas o build desta fonte precisa ser repetido em um ambiente com Android SDK configurado; também não há dispositivo físico/emulador conectado ao ambiente para capturar Logcat ou realizar teste visual interativo. Se ainda ocorrer crash depois deste APK, o diagnóstico definitivo precisa do trecho do Logcat no momento do crash (`adb logcat -b crash -d`), pois o projeto não contém um relatório de crash do aparelho.
