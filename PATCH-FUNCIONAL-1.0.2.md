# PAINEL SANTOS — patch funcional 1.0.2

## Correções desta entrega

- **Hz 60/90:** o painel continua usando somente `Display.Mode` exposto pelo aparelho; os modos não suportados ficam cinza. A aplicação deixou de exigir que `min_refresh_rate` já exista: settings ausentes são tratados como sem override, 60/90 são enviados por Shizuku e confirmados pelos settings ou pela taxa efetiva do display. O rollback usa `settings delete` quando o valor original não existia.
- **Resolução/DPI:** mantida a leitura real de `wm size`/`wm density`, confirmação após cada alteração e rollback quando o firmware recusar size ou density.
- **Touch/CPU:** touch procura os caminhos `poll_rate`/`report_rate` expostos pelo driver; CPU aceita os caminhos comuns de governor e salva o caminho/valor original para restauração.
- **Renderer:** adicionada seleção funcional da camada da overlay: Automático, OpenGL ES/Hardware (backend de hardware do Android) e Skia software. Isso altera somente o painel. Android não fornece API pública para forçar Vulkan/OpenGL/Skia dentro de um jogo externo; Vulkan é mostrado como capacidade e permanece no backend automático quando suportado.
- **Painel visual:** overlay reorganizada no modelo da referência: trilho lateral de abas, cabeçalho com aba ativa, cards azul-marinho, destaque magenta, botões arredondados e switches coloridos. As três áreas continuam sendo Toque, Tela e Ajustes/Otimização.
- **Falha de capacidade:** recursos de CPU/GPU/touch/rede que falharem por falta de path gravável deixam de parecer um loop; o switch volta para desligado, mostra o erro e fica cinza até uma nova sessão. A UI não declara otimização falsa.
- **Fechar no X:** o X remove somente a overlay e encerra o serviço, sem abrir MainActivity. Foi corrigida uma corrida em que uma resposta atrasada da revalidação online podia abrir a tela de bloqueio depois do X.
- **Compatibilidade:** `minSdk 29` (Android 10), `targetSdk 35` e APIs condicionais para execução do Android 10 ao Android 16.

## Validação

- Scan estático de Java/Kotlin/XML realizado.
- Build realizado com `JAVA_HOME` JDK 17: `:app:assembleDebug` e `:app:lintDebug` passaram.
- APK debug validado como assinado com APK Signature Scheme v2. Pacote: `painel.sensi.santos`, `versionCode 3`, `minSdk 29`, `targetSdk 35`.
- As funções de shell só podem ser confirmadas em aparelho real com Shizuku autorizado; o firmware pode deixar CPU/GPU/touch/cache indisponíveis e, nesses casos, o painel mostra o erro real e não declara sucesso.
