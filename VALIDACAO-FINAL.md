# PAINEL SANTOS R9 — validação local

Data: 2026-09-11  
Versão: `versionCode 9`, `versionName 1.0.8`  
Pacote: `painel.sensi.santos`

## Correções desta revisão

- A abertura agora usa somente uma `ProgressBar` indeterminada, centralizada. O `ImageView`/logo foi removido do splash.
- O Manifesto declara `android.permission.WRITE_SETTINGS`; nenhum botão/card/status adicional de permissão foi adicionado ao painel. `pointer_speed` verifica a capacidade local somente no momento da tentativa.
- `WRITE_SETTINGS` é verificada somente pela função local que realmente usa `Settings.System`; ela não bloqueia Shizuku, long-press, animações ou outras funções independentes.
- O botão X remove a overlay, encerra o serviço e abre a `MainActivity` com `ACTION_KEY_SCREEN`. A Activity força a tela de key e não inicia o painel automaticamente nessa abertura.
- Sliders de ponteiro, atraso de toque e escala de animação passam a enviar explicitamente o valor solicitado ao executor, inclusive quando o switch-mestre ainda está desligado. Preferências só são salvas após retorno de sucesso.
- Cada função dependente de Shizuku continua condicionada à autorização e ao teste remoto obrigatório; funções locais não herdam esse bloqueio.

## Base mantida

- Fluxo da key instrumentado com `DEBUG_FLOW`, callback fraco, `Request.cancel()` e cancelamento no `MainActivity.onDestroy()`.
- Key retorna à Main Thread; timeout, DNS, conexão recusada, HTTP 5xx/429, JSON inválido e servidor indisponível têm mensagens e logs próprios.
- Overlay verifica `Settings.canDrawOverlays`, usa `TYPE_APPLICATION_OVERLAY` em API 26+, está registrada no Manifesto e remove a janela no `onDestroy()`.
- Autorização do Shizuku é observada por listener; binder morto remove a autorização visual e bloqueia funções dependentes.
- API 13.1.5 usa exclusivamente o processo remoto autorizado por `Shizuku.newProcess` via reflexão cacheada da assinatura privada dessa versão. Não há `Runtime.exec`, `ProcessBuilder`, `su` local ou fallback local.
- stdout e stderr são drenados em paralelo; comandos são serializados; stdin/streams são fechados; `alive()`/`waitForTimeout()` são os métodos próprios de `ShizukuRemoteProcess`.
- O diagnóstico preserva comando, stdout, stderr, exit code, criação do processo, binder e exceção.
- O primeiro diagnóstico é `echo SANTOS_SHIZUKU_OK`; sem stdout esperado e exit code 0, os switches permanecem bloqueados.

## Verificações locais executadas

- `./gradlew --no-daemon --max-workers=1 clean assembleDebug lintDebug` — **PASS**.
- `aapt dump badging` — **PASS**: minSdk 29, targetSdk 35, versionCode 9, versionName 1.0.8.
- A fonte declara `android.permission.WRITE_SETTINGS`; não declara `WRITE_SECURE_SETTINGS`.
- `zipalign -c -P 16 -v 4` — **PASS**.
- `apksigner verify --verbose` — **PASS**: assinatura debug v2 válida, 1 signer.
- Lint — **PASS**, com avisos não bloqueantes.
- SHA-256 do APK R9: `7a7c9e6e6fbe72159d84bbd7ebeb39224118c61648f91e026d1b66bdf86e6cc1`.

## Teste físico / Logcat

**Não executado: não há aparelho ou emulador ADB conectado neste ambiente.**

Os comandos disponíveis retornaram:

```text
adb devices -l
List of devices attached

adb shell echo SANTOS_SHIZUKU_OK
adb: no devices/emulators found
```

Portanto, o APK é um build debug instalável, mas ainda não foi validado fisicamente. Não é possível afirmar a operação da ponte, das funções ou a causa exata do crash original sem o Logcat do aparelho.

## Sequência física pendente

1. Conectar e autorizar um aparelho por ADB.
2. Capturar `adb logcat -c` e `adb logcat AndroidRuntime:E DEBUG_FLOW:D ShizukuBridge:E OverlayService:E '*:S'`.
3. Abrir o painel sem Shizuku e confirmar que a tela abre.
4. Iniciar Shizuku, autorizar o app e tocar **Testar ponte**.
5. Confirmar primeiro `stdout=SANTOS_SHIZUKU_OK` e `exit=0`.
6. Testar `pointer_speed` local sem Shizuku; depois testar long-press/animações, switches, resolução/DPI e refresh com as capacidades remotas correspondentes.
7. Tocar X e confirmar retorno à tela de key, sem reabrir diretamente a overlay.
8. Salvar o Logcat com `FATAL EXCEPTION`, classe e linha, se ainda houver crash.
