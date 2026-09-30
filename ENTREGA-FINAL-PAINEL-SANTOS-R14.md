# PAINEL SANTOS — entrega final R14

## Resultado

- Projeto: `painel.sensi.santos`
- Versão: `1.0.8` — `versionCode 9`
- APK: `app/build/outputs/apk/debug/app-debug.apk`
- Build executado: `./gradlew clean assembleDebug`
- Resultado: **BUILD SUCCESSFUL**
- SHA-256 do APK gerado: `ccdc4c6fef793d629d8aefc206c2506cbf942a5483489a78ce55b2fef98beccf`
- Assinatura: debug keystore, APK Signature Scheme v2 verificado

## Arquivos alterados — caminhos completos

1. `/home/user/work/painel-santos/app/src/main/AndroidManifest.xml`
2. `/home/user/work/painel-santos/app/src/main/java/painel/sensi/santos/DisplayManagerHelper.java`
3. `/home/user/work/painel-santos/app/src/main/java/painel/sensi/santos/ShizukuBridge.java`
4. `/home/user/work/painel-santos/app/src/main/java/painel/sensi/santos/OverlayService.java`
5. `/home/user/work/painel-santos/app/src/main/kotlin/painel/sensi/santos/ShizukuManager.kt`
6. `/home/user/work/painel-santos/app/src/main/kotlin/painel/sensi/santos/ShellManager.kt`

Arquivos protegidos conferidos e **não alterados**:

- `app/src/main/kotlin/painel/sensi/santos/LicenseValidator.kt`
- `app/src/main/kotlin/painel/sensi/santos/KeyManager.kt`
- `app/src/main/java/painel/sensi/santos/LicenseLockActivity.java`

## Trechos exatos corrigidos

### 1. Manifest — permissão para o método CAT/Reflection

```xml
<!-- CORRIGIDO ALONGAR TELA: conceder via ADB/Shizuku:
     pm grant painel.sensi.santos android.permission.WRITE_SECURE_SETTINGS -->
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
    tools:ignore="ProtectedPermissions" />
```

Concessão no aparelho:

```bash
adb shell pm grant painel.sensi.santos android.permission.WRITE_SECURE_SETTINGS
```

### 2. `am compat` — ordem corrigida e verificação prévia

O código mantém a ordem exigida para alterações reais:

```java
am compat <ação> <REGRA> painel.sensi.santos
```

A chamada `am compat list painel.sensi.santos` é executada somente como sonda diagnóstica antes das alterações e seu stdout/stderr/exit code é registrado. No AOSP Android 11–16 a ação `list` não existe no `am compat`, então ela pode retornar `exit=255` com `Unknown or invalid change`. Esse `255` não bloqueia o core CAT/Reflection; o resultado decisivo é o exit code do `am compat enable/disable` real. Se a alteração real falhar, o stderr/exit code aparece na mensagem e o preset faz rollback.

Regras usadas:

- `OVERRIDE_MIN_ASPECT_RATIO`
- `OVERRIDE_MIN_ASPECT_RATIO_LARGE`
- `FORCE_RESIZE_APP`

O caminho de execução verifica `Shizuku.pingBinder()` e permissão antes de cada processo remoto. Cada processo registra na tag `SHIZUKU_DEBUG`:

- comando completo;
- stdout;
- stderr;
- exit code.

Exit code diferente de zero não é convertido em sucesso: o erro real segue para o callback/Toast e, durante um preset, dispara rollback.

### 3. Método CAT — Reflection + `IWindowManager`

`DisplayManagerHelper.java` é entregue completo como arquivo separado e dentro do ZIP do source. O núcleo usa:

```java
// CORRIGIDO ALONGAR TELA: caminho principal sem shell.
setForcedDisplaySize(DISPLAY_ID, width, height);
setForcedDisplayDensity(DISPLAY_ID, density);
clearForcedDisplaySize(DISPLAY_ID);
clearForcedDisplayDensity(DISPLAY_ID);
```

O fallback, somente se Reflection não estiver disponível ou não confirmar, usa Shizuku:

```java
new String[]{"wm", "size", width + "x" + height}
new String[]{"wm", "density", String.valueOf(density)}
new String[]{"wm", "size", "reset"}
new String[]{"wm", "density", "reset"}
```

**Não existe execução de `wm overscan` no source final.**

### 4. Fórmula do preset ESTICADO

A largura física é preservada; somente a altura normalizada é reduzida para preencher a tela:

```java
// CORRIGIDO ALONGAR TELA.
int L = physical.portraitWidth;
int H = physical.portraitHeight;
float aspectoAlvo = clampRatio(requestedRatio, physical.physicalRatio());
int novaAltura = Math.round(L * aspectoAlvo);
int novaDpi = Math.round(physical.density * novaAltura / (float) H);
```

O alvo é reorientado conforme a rotação atual, sem trocar lados artificialmente. Não há `ScaleX(-1)`, matriz negativa, `fitCenter`, letterbox, injeção de input, `uinput`, `sendevent`, `/dev/input` ou alteração de arquivos do jogo.

### 5. Três presets

A UI mantém as abas/estrutura existentes e adiciona ações explícitas:

```java
// CORRIGIDO ALONGAR TELA.
PADRÃO    -> clearForcedDisplaySize/Density + settings reset + compat disable
TELA CHEIA -> resolução física completa + cutout/policy/force-resize + compat
ESTICADO  -> fórmula CAT + cutout/force-resizable + compat LARGE/FORCE
```

Cada ação salva antes um snapshot de:

- tamanho físico e override original;
- densidade física e override original;
- `display_cutout_mode`;
- `policy_control`;
- `force_resizable_activities`;
- estado das três regras `am compat`.

Em falha parcial, o snapshot é restaurado e o resultado informa se o rollback foi confirmado ou falhou.

### 6. Limpeza de cache, RAM, renderer e thermal

- Limpeza: executa `sync` e somente depois `echo 3 > /proc/sys/vm/drop_caches`; sucesso exige exit code `0` nas etapas.
- RAM: `am kill-all`; falha mostra exit code real e não impede outras funções.
- Renderer externo: permanece explicitamente **não suportado** sem API legítima package-scoped; não usa `setprop` inseguro.
- Thermal: permanece **não suportado**; não há bypass térmico.

## Checklist de teste no Samsung Galaxy A07 / Android 16

### Verificações realizadas neste ambiente

- [x] `./gradlew clean assembleDebug`
- [x] APK gerado em `app/build/outputs/apk/debug/app-debug.apk`
- [x] assinatura APK v2 verificada
- [x] package/version/minSdk/targetSdk conferidos no APK
- [x] `WRITE_SECURE_SETTINGS` presente no manifest
- [x] arquivos de licença protegidos não alterados
- [x] `am compat` real não é bloqueado pela sonda `list` inválida do AOSP
- [x] `am compat enable/disable REGRA PACOTE` permanece com exit code real
- [x] ordem errada `am compat <ação> painel.sensi.santos` ausente
- [x] `wm overscan` ausente do código executável/source final
- [x] nenhuma injeção/espelhamento/coordenada invertida encontrada na varredura

### Procedimento manual no aparelho

1. Instalar o APK e iniciar o Shizuku.
2. Autorizar o app no Shizuku.
3. Conceder:

   ```bash
   adb shell pm grant painel.sensi.santos android.permission.WRITE_SECURE_SETTINGS
   ```

4. Abrir o painel e testar `PADRÃO`.
5. Testar `TELA CHEIA`; confirmar resolução física, sem bordas/letterbox e com lados preservados.
6. Ajustar o slider entre `1,50` e `2,22` e testar `ESTICADO` (padrão `1,99`).
7. Confirmar no Logcat tag `SHIZUKU_DEBUG`:
   - `am compat list painel.sensi.santos` antes da alteração;
   - `am compat enable/disable REGRA painel.sensi.santos`;
   - stdout/stderr/exit code.
8. Testar rollback desligando/reiniciando o binder durante uma operação.
9. Testar restauração com `PADRÃO` e conferir resolução/DPI/settings originais.
10. Confirmar visualmente: tela preenchida, sem barras pretas, esquerda continua esquerda e direita continua direita.

Não há aparelho físico conectado a este ambiente; portanto a confirmação visual final e a concessão real via ADB precisam ser feitas no Galaxy A07. O código confirma dimensões/DPI/settings após cada alteração, mas não declara uma inspeção visual de hardware que não foi executada.

## Resumo solicitado

- Método usado: **CAT Resolution Pro — Reflection em `IWindowManager`**, com fallback Shizuku `wm size/wm density`.
- Aspecto aplicado: slider físico de `1,50` a `2,22`, padrão `1,99`; largura física preservada e DPI recalculado proporcionalmente.
- Erro 255: **corrigido no caminho que bloqueava a resolução**. A sonda `am compat list` pode continuar registrando `exit=255` porque não é uma ação válida no AOSP; agora isso não impede Reflection/`wm size`. Os comandos reais usam `am compat <ação> <REGRA> <PACOTE>` e seu exit code é decisivo. A execução em um Galaxy A07 real ainda precisa ser confirmada no Logcat do aparelho.
- `wm overscan`: removido do caminho de execução.
