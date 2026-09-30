# PAINEL SANTOS — auditoria e correção R10

Data: 2026-09-11  
Pacote: `painel.sensi.santos`  
Base: `PAINEL-SANTOS-r9-source-clean.zip`

## Causa reproduzida por inspeção do código

A revisão anterior colocava `pointer_speed` no mesmo caminho de `long_press_timeout` e escalas de animação:

1. `OverlayService.addSlider()` chamava `applySafeSlider()` para `pointer_speed`.
2. `applySafeSlider()` exigia `Settings.System.canWrite()`, depois `ShizukuBridge.hasPermission()` e `bridgeVerified`.
3. A operação era encaminhada a `ShizukuBridge.applySafeSettings()`, que executava `settings put system pointer_speed` remotamente.
4. `updateCapabilityStates()` aplicava `authorized && bridgeVerified` a controles independentes, e o listener de slider bloqueava se qualquer operação estivesse pendente.
5. `addShizukuCard()` adicionava o botão visual de WRITE_SETTINGS; `permissionStatus()` também expunha esse estado.
6. `LicenseValidator.startMonitoring()` abria `LicenseLockActivity` para qualquer resultado `!valid`, incluindo timeout, DNS, erro HTTP temporário e JSON malformado.

Isso explica por que `pointer_speed` não funcionava com Shizuku desligado ou sem o teste da ponte.

## Patch aplicado

- `pointer_speed` foi separado em caminho local no `OverlayService` usando somente `Settings.System.getInt()/putInt()` e `Settings.System.canWrite()` no momento da tentativa.
- O valor atual é lido antes da alteração; o valor solicitado é convertido para o intervalo Android `-7..7`; o valor é relido após `putInt()`.
- A preferência e o valor original só são salvos após confirmação da leitura. Em falha, o slider visual volta ao valor anterior, a preferência não é atualizada e o erro/causa real fica no status e no Logcat. Se houve mudança parcial, há tentativa de rollback local confirmada.
- `pointer_speed` não chama `ShizukuBridge`, `ShellManager`, `Runtime.exec()`, `ProcessBuilder` ou shell.
- `long_press_timeout` e escalas de animação continuam no caminho remoto Shizuku, sem exigir WRITE_SETTINGS como bloqueio global.
- O botão/card/status adicional de “Modificar configurações do sistema” foi removido. A declaração `WRITE_SETTINGS` continua no Manifesto.
- O bloqueio por operação passou a ser por controle (`slider_pointer_speed`, `slider_long_press`, `slider_animation_scale`, cada switch), e não por `pendingToggleOperations.isEmpty()` global.
- O teste remoto continua começando por `echo SANTOS_SHIZUKU_OK`; a ponte só é marcada como validada com stdout contendo o marcador e exit code 0.
- A licença agora persiste sessão local, fingerprint SHA-256 da key e `expires_at`/campos equivalentes retornados pelo servidor. Falhas transitórias não abrem `LicenseLockActivity`; somente resposta explícita inválida/expirada/revogada pode bloquear.
- O intervalo do monitor deixou de ser de poucos segundos e passou para 15 minutos.
- A tentativa especulativa de `pm grant WRITE_SECURE_SETTINGS` e a declaração correspondente foram removidas.

## Evidência estática realizada

- Não foi encontrado uso de `Runtime.exec`, `ProcessBuilder`, `su` local ou `destroyForcibly` no código executável.
- A execução remota está centralizada em `Shizuku.newProcess` e usa `ShizukuRemoteProcess.alive()`, `waitForTimeout()` e `waitFor()` após confirmação de término.
- stdout e stderr são drenados em threads separadas; stdin e streams são fechados; os comandos são serializados por `PROCESS_LOCK`/fila.
- Não há `pointer_speed` em `ShizukuBridge.java` nem `ShellManager.kt` após o patch.
- O Manifesto contém `android.permission.WRITE_SETTINGS` e não contém `WRITE_SECURE_SETTINGS`.
- Não há texto de botão/card `PERMITIR MODIFICAÇÕES DO SISTEMA` no código do painel.

## Build local realizado

O projeto foi compilado com sucesso após disponibilizar temporariamente Android SDK 35, Build Tools 35.0.0 e JDK 17:

```text
./gradlew --no-daemon --max-workers=1 clean assembleDebug
BUILD SUCCESSFUL
```

Validações do APK:

- pacote: `painel.sensi.santos`;
- minSdk: 29;
- targetSdk: 35;
- `WRITE_SETTINGS` presente;
- `WRITE_SECURE_SETTINGS` ausente;
- `zipalign` passou;
- assinatura debug v2 válida;
- SHA-256: `e039acfd379b940b46b71706a46a137c47a91fa530647a144f66cceb216307bc`.

Não há aparelho/emulador ADB conectado nesta sessão. Portanto, não é possível afirmar funcionamento físico da API `Settings.System`, da permissão especial, do Shizuku ou de qualquer driver/firmware.

## Teste físico pendente

Executar em aparelho conectado, sem declarar sucesso antes dos resultados:

```bash
adb logcat -c
adb logcat -v threadtime AndroidRuntime:E DEBUG_FLOW:D ShizukuBridge:E OverlayService:E '*:S'
```

Ordem mínima:

1. ativar uma key válida de 30 dias;
2. abrir o painel com Shizuku desligado;
3. alterar `Velocidade do ponteiro`;
4. confirmar com `adb shell settings get system pointer_speed`;
5. ligar Shizuku, autorizar o app e tocar `TESTAR PONTE SHIZUKU`;
6. verificar stdout `SANTOS_SHIZUKU_OK` e exit code `0`;
7. testar apenas então CPU/GPU/touch/TCP/Game Mode/Doze/wm/refresh;
8. provocar falha e confirmar rollback visual, de preferência também lendo o setting pelo ADB;
9. coletar Logcat completo se qualquer etapa falhar.

Conclusão atual: o bloqueio lógico identificado foi corrigido e o APK debug foi compilado/validado estruturalmente, mas ele ainda **não pode ser declarado funcional fisicamente** sem o teste em aparelho.
