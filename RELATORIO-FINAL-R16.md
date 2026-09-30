# PAINEL SANTOS 1.0.8 — R16

## Causas e correções
- Renderer: o fluxo anterior lia propriedades globais e permitia aparência de aplicação; agora só usa APIs de capability e declara renderer externo/painel não confirmado e não suportado.
- Refresh: escrita fixa do caminho de touch foi removida; `DisplayManager.getSupportedModes()` continua sendo a fonte dos modos, e peak/min usam snapshot, leitura, aplicação e readback.
- Captura: o Android não expõe sessão externa confirmável; o painel não oculta nem persiste sucesso falso.
- Package: `EditText` recebe foco, cursor, teclado, seleção e validação; o próprio package é rejeitado antes de qualquer compat externo.
- Display: estado `APPLYING_SETTINGS`, validação/rollback e proteção de lifecycle permanecem serializados.
- Logo/configurações: o `S` lateral usa `santos_logo` com proporção preservada e abre CONFIGURAÇÕES; color wheel persiste seleção explícita e restaura cor original.

## Arquivos alterados
- `app/src/main/java/painel/sensi/santos/OverlayService.java`
- `app/src/main/java/painel/sensi/santos/DisplayManagerHelper.java`
- `app/src/main/kotlin/painel/sensi/santos/ShellManager.kt`
- `app/src/main/java/painel/sensi/santos/ColorWheelView.java`

## Funções realmente corrigidas
Renderer somente leitura; HZ real e atualização por `DisplayListener`; package externo editável/validado; Brevent sem falso ativo; ocultação de transmissão recusada quando não confirmável; snapshot de cor; color wheel; informações do dispositivo; imagem e navegação do S; build e validações estáticas.

## Não suportado pelo Android
Renderer externo/package-scoped; FPS real de jogo sem API/permissão legítima; execução de shell arbitrário via API pública do Brevent; detecção segura de gravação externa; touch driver sem API pública do fabricante; One UI por API pública.

## Readback e testes
- `./gradlew clean assembleDebug`: sucesso.
- APK: package `painel.sensi.santos`, versionName `1.0.8`, versionCode `9`, minSdk `29`, targetSdk `35`.
- Assinatura v2 e zipalign: sucesso.
- Scan proibido: sucesso; hashes dos três arquivos protegidos preservados.
- Galaxy A07/ADB, rotação real, Shizuku, Brevent e transmissão: não testados por falta de aparelho conectado.
