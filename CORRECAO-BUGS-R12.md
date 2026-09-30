# Correção dos dois bugs — PAINEL SANTOS R12

## 1. Análise do Bug 1 — Shizuku fecha o painel

### Diagnóstico do código original

O arquivo não possui `ShizukuShell.newCommand()`. Na API Shizuku `13.1.5`, a execução real é `Shizuku.newProcess(...)`, encapsulada por `ShizukuBridge.executeCommand()`.

1. `app/src/main/kotlin/painel/sensi/santos/ShellManager.kt` — antigo `Executors.newSingleThreadExecutor()` e `executor.execute { ... }` nos métodos de operação e sondagem. Embora já fosse uma thread de trabalho, isso não atendia ao requisito de manter toda a execução remota no escopo de coroutine `Dispatchers.IO`.
2. `ShizukuBridge.java` — os fluxos assíncronos de status, teste da ponte, settings, `wm size/density`, overscan e restauração usavam vários `new Thread(...)`. A implementação já serializava os processos remotos e tinha timeout, mas a fronteira entre executor, binder e callback não era uniforme.
3. `OverlayService.java` — o código original registrava `addBinderReceivedListenerSticky(...)`; a chegada/morte do binder precisava ficar explicitamente ligada ao ciclo de vida do serviço com `addBinderReceivedListener(...)` e remoção no `onDestroy()`.
4. `OverlayService.applySafeSlider(...)` — o callback podia continuar o fluxo de `SeekBar`, preferências e status depois de o serviço ter sido destruído. Foi adicionada a guarda `if (destroyed) return` antes de qualquer View/estado.
5. `MainActivity.java` — o fluxo de licença já tinha `uiAlive()` (`!isFinishing() && !isDestroyed()`) e cancelava requisições no `onDestroy()`. Ele foi preservado e também recebeu a entrega da key ativa ao serviço, sem obrigar o usuário a persistir a key.
6. `ShizukuBridge.java` — a chamada de `newProcess(...)` já estava dentro de `try/catch`, mas agora a API pública, o executor e o retorno registram explicitamente `SHIZUKU_DEBUG` antes/depois/erro e devolvem `CommandResult(false, ...)` em falhas.
7. Os dois threads restantes em `ShizukuBridge` são somente drenadores de `stdout`/`stderr` para impedir deadlock de buffer. Eles não criam nem executam comandos e não atualizam UI; a execução do processo continua no worker Coroutine/IO.

### Correção aplicada

- `ShizukuManager.launchIo(...)` usa `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.
- Todos os fluxos assíncronos de `ShizukuBridge` foram migrados para `launchIo`.
- `ShellManager` passou de `Executor` para `CoroutineScope` IO.
- Todos os callbacks de ponte usam `MAIN_HANDLER`/`Handler(Looper.getMainLooper())`.
- `OverlayService` só atualiza Views quando `destroyed == false`; a mensagem também passa pela Main Looper.
- `Shizuku.addBinderReceivedListener(...)`, `addBinderDeadListener(...)` e os respectivos `remove...` estão no ciclo de vida do serviço.
- Binder morto invalida teste, capability e operações pendentes, exibe mensagem em português e não encerra o processo.
- Erros de shell exibem resultado amigável e ficam no Logcat.

### Linhas principais na versão corrigida

- `ShizukuBridge.java:284-305`: API pública protegida por `try/catch` e logs.
- `ShizukuBridge.java:374-510`: processo remoto serializado, timeout, resultado, exceção e logs.
- `ShizukuManager.kt:20-65`: execução/callback em coroutine IO/Main.
- `ShellManager.kt:36-40,155,310-352`: fila IO e callback Main.
- `OverlayService.java:251-252,349-374`: listeners e remoção no lifecycle.
- `OverlayService.java:2117`: guarda de callback tardio do slider.
- `MainActivity.java:112`: guarda `uiAlive()` preservada.

## 2. Análise do Bug 2 — liberação por dias da key

### Erro do código original

1. `LicenseValidator.Result` só carregava `expiresAtMillis`; não havia `daysRemaining` nem uma decisão única de acesso.
2. `hasValidLocalSession()` fazia somente uma decisão binária (`expires <= 0 || expires > now`). Não existia a regra de 7 dias.
3. `saveLocalSession()` só gravava `SESSION_EXPIRES_AT` quando a resposta tinha data positiva. Se uma resposta válida nova viesse sem data, a expiração antiga podia continuar no cache.
4. Não havia modelo de key para diferenciar data ausente/trial, key expirada e key com acesso total.
5. Não havia botão de revalidação nem contador de dias no painel.

### Regra corrigida

A regra agora é centralizada em `KeyManager.accessFromExpiration(...)`:

```kotlin
val differenceMillis = expiresAtMillis - currentAtMillis
val days = if (differenceMillis <= 0L) 0L
           else differenceMillis / (1000L * 60L * 60L * 24L)

val expired = expiryKnown && (expiresAtMillis <= currentAtMillis || days <= 0L)
val fullAccess = valid && !expired && days != null && days >= 7L
```

- `diasRestantes >= 7`: libera recursos licenciados.
- `diasRestantes < 7`: bloqueia recursos restritos e mostra `Disponível para keys com 7+ dias`.
- `diasRestantes <= 0`: key expirada, bloqueio total.
- Expiração nula/inválida: `trial`, `daysRemaining == null`; não é convertida em zero e não preserva uma expiração velha.
- Epoch/`Instant` é tratado em UTC; os logs exibem `dataAtual`, `dataExpiracao`, `diasRestantes` e `resultadoLiberacao`.
- O parser aceita epoch em segundos/milisegundos, ISO-8601, `yyyy-MM-dd HH:mm:ss`, `yyyy-MM-dd'T'HH:mm:ss`, `dd/MM/yyyy`, `MM/dd/yyyy` e `yyyy-MM-dd`, sempre convertendo o valor para UTC.
- A nova validação substitui fingerprint, expiração e indicador de data do cache; uma key diferente não reaproveita o cache anterior.

### Linhas principais na versão corrigida

- `KeyManager.kt:25-50`: cálculo UTC, comparação e logs.
- `KeyManager.kt:54-80`: leitura/gravação do cache sem expiração velha.
- `KeyManager.kt:83-100`: limpeza completa e validação da sessão.
- `LicenseValidator.kt:54-63`: `Result.daysRemaining`.
- `LicenseValidator.kt:216-225`: resposta válida só é aceita como expirada quando o cálculo indicar `<= 0`.
- `LicenseValidator.kt:306-330`: parse seguro de datas.
- `LicenseValidator.kt:334-362`: integração com `KeyManager` e revalidação.
- `LicenseKeyInfo.java:1-46`: modelo imutável da decisão.
- `OverlayService.java:568-580`: contador e botão `Revalidar key`.
- `OverlayService.java:1265-1343`: texto de dias, limpeza/revalidação e enabled/alpha dos botões.
- `OverlayService.java:1840-1971`: gates dos toggles, sliders e ações remotas.

## 3. Código completo corrigido, organizado por arquivo

O ZIP entregue contém o projeto completo, não apenas um diff. Todos os arquivos originais foram preservados e os seguintes foram alterados/adicionados:

### Shizuku / execução

- `app/src/main/java/painel/sensi/santos/ShizukuBridge.java`
- `app/src/main/kotlin/painel/sensi/santos/ShizukuManager.kt`
- `app/src/main/kotlin/painel/sensi/santos/ShellManager.kt`
- `app/src/main/java/painel/sensi/santos/OverlayService.java`
- `app/src/main/java/painel/sensi/santos/MainActivity.java`
- `app/src/main/AndroidManifest.xml` — preservado/conferido, incluindo provider Shizuku, `INTERNET`, overlay, foreground service e `WRITE_SETTINGS`.

### Licença / key

- `app/src/main/kotlin/painel/sensi/santos/LicenseValidator.kt`
- `app/src/main/kotlin/painel/sensi/santos/KeyManager.kt` — novo.
- `app/src/main/java/painel/sensi/santos/LicenseKeyInfo.java` — novo modelo.
- `app/src/main/java/painel/sensi/santos/KeyValidator.java` — preservado para validação de entrada.
- `app/src/main/java/painel/sensi/santos/LicenseLockActivity.java` — preservado e compatível com a limpeza completa do cache.

O arquivo `MATRIZ-CAPACIDADES.md` original e as demais classes/UI/build também estão no ZIP para permitir importação direta no Android Studio.

Cada linha nova/alterada nos arquivos de correção contém comentário `// CORRIGIDO BUG1: ...` ou `// CORRIGIDO BUG2: ...`, conforme o bug tratado.

## 4. Checklist de teste no aparelho

### Preparação

- Instalar o APK/build gerado do projeto corrigido.
- Ativar Shizuku e autorizar `painel.sensi.santos`.
- Limpar o Logcat antes do teste.
- Usar filtro:

```bash
adb logcat -c
adb logcat -v threadtime \
  SHIZUKU_DEBUG:D DEBUG_FLOW:D ShizukuBridge:E OverlayService:E \
  LicenseValidator:E KeyManager:D '*:S'
```

### Bug 1 — Shizuku

1. Abrir uma key válida e abrir a bolha.
2. Abrir a aba `AJUSTES`.
3. Tocar `Autorizar` quando aplicável e depois `TESTAR PONTE SHIZUKU`.
4. Esperar no Logcat:
   - `SHIZUKU_DEBUG: antes de executar...`
   - `SHIZUKU_REMOTE_PROCESS_CREATED`
   - `SHIZUKU_DEBUG: depois de executar... exit=0`
   - `SANTOS_SHIZUKU_OK` no stdout/diagnóstico.
5. Acionar cada caminho remoto individualmente: teste echo, settings, `wm size/density`, refresh, cache, RAM e um toggle suportado pelo aparelho.
6. Observar que o painel permanece aberto, a mensagem aparece em português e nenhum `FATAL EXCEPTION`/`AndroidRuntime` ocorre.
7. Matar/desativar o binder do Shizuku durante um comando. Esperar:
   - `OVERLAY_SHIZUKU_BINDER_DEAD`;
   - `Binder do Shizuku morreu; ponte invalidada`;
   - controles remotos desabilitados;
   - mensagem amigável na overlay;
   - nenhum crash.
8. Reativar o binder sem reiniciar o app. Esperar `OVERLAY_SHIZUKU_BINDER_RECEIVED`, nova sondagem e atualização do status.
9. Fechar/reabrir a overlay enquanto um comando demora. O callback tardio deve ser ignorado após `onDestroy()`.

### Bug 2 — dias da key

1. Validar uma key com expiração exatamente 8–30 dias à frente. No painel, confirmar:
   - `Licença: N dias restantes · acesso total`;
   - botões licenciados com `isEnabled == true` e `alpha == 1f`;
   - toggles remotos liberados após binder/teste técnico.
2. Validar uma key com expiração entre 1 e 6 dias. Confirmar:
   - contador correto em UTC;
   - controles restritos desabilitados;
   - texto `Disponível para keys com 7+ dias`;
   - botão `Revalidar key` continua disponível.
3. Validar uma key exatamente 7 dias à frente. Confirmar que entra na faixa de acesso total (`>= 7`, não `> 7`).
4. Validar uma key expirada ou com expiração dentro de menos de 24 horas. Confirmar `diasRestantes=0`, status expirado e bloqueio total dos controles licenciados.
5. Responder a API com data nula/inválida em ambiente de teste/mock. Confirmar `trial · expiração não informada`, `daysRemaining=null` e nenhum crash; não deve reutilizar a expiração de uma key anterior.
6. Ativar uma key A, depois uma key B com outra expiração. Confirmar que o contador de B não usa o cache/fingerprint de A.
7. Tocar `Revalidar key`. Confirmar no Logcat `cache de validação limpo`, nova requisição online e atualização imediata do contador/estado dos botões.
8. Alterar o relógio/timezone do aparelho apenas para teste controlado. Os logs `dataAtual`/`dataExpiracao` devem permanecer em UTC e o cálculo não deve depender do fuso local.

## Limitação de validação desta entrega

A validação estática foi executada e os delimitadores Java/Kotlin estão balanceados. O Gradle 8.7 foi iniciado, mas `assembleDebug` não chegou a compilar porque este ambiente não possui Android SDK (`SDK location not found`; não há `ANDROID_HOME`, `ANDROID_SDK_ROOT` ou `local.properties`). Portanto, o build físico e o comportamento real do binder/firmware ainda precisam ser confirmados no Android Studio/aparelho com o checklist acima.
