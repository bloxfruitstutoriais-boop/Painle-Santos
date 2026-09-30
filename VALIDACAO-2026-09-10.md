# PAINEL SANTOS — validação da correção final r7

## Resultado do source entregue r7

- APK debug: `PAINEL-SANTOS-debug.apk`
- Pacote: `painel.sensi.santos`
- `versionCode 8`, `versionName 1.0.7`
- `minSdk 29`, `targetSdk 35`, `compileSdk 35`
- SHA-256 do APK entregue: `ead229c134b26064d9921491d22a332e67539ba17e09636ec520e4308e92b10f`

## Correções aplicadas nesta r7

- O onboarding de quatro páginas foi removido do fluxo e do source da Activity. Depois da capa, o app vai diretamente para a tela de key.
- A abertura tem somente a capa Santos Team e um único `ProgressBar` circular nativo; não há animações customizadas, slide ou quatro abas.
- A solicitação automática de permissão do Shizuku durante a abertura da overlay foi removida.
- Detectar o binder ou conceder autorização apenas atualiza o status. Nenhum `echo`, `settings`, probe de kernel ou comando remoto é executado automaticamente.
- O primeiro comando remoto só pode ser iniciado ao tocar explicitamente em **Testar ponte**. Sem o echo confirmado, os switches permanecem bloqueados.
- O callback do diagnóstico agora é uma fronteira protegida: exceções na atualização da UI são registradas com `Log.e`, stack trace, estado da ponte e diagnóstico, em vez de derrubarem o processo sem informação.
- O worker do teste também registra exceções fora do executor e devolve falha explícita ao painel.
- `ShizukuBridge` usa somente `Shizuku.newProcess` remoto autorizado da API 13.1.5, via reflexão necessária porque `newProcess` é privado nessa versão.
- `ShizukuRemoteProcess.alive()` e `waitForTimeout()` próprios são usados; `waitFor()` somente após confirmação de término. Não há `Runtime.exec`, `ProcessBuilder`, `su` local, shell local, `Process.isAlive`, `exitValue` antecipado ou `destroyForcibly`.
- stdout e stderr são drenados em threads paralelas; stdin e streams são fechados; processos são serializados.
- O diagnóstico separa Shizuku não iniciado, binder indisponível, app não autorizado, processo remoto não criado, exit code recusado pelo firmware e exceção real. Mostra comando, stdout, stderr, exit code, resultado e exceção; há detalhes expansíveis e cópia.
- O binder morto invalida a ponte, bloqueia funções dependentes e restaura visualmente switches que estavam pendentes.
- Switches e sliders dependentes só persistem após exit code 0 e confirmação posterior quando aplicável. Falhas restauram o estado confirmado e bloqueiam toques repetidos durante “Aplicando…”.
- CPU/GPU/touch são sondados antes de habilitar escrita. Quando o kernel/driver não expõe suporte, ficam cinza com “não suportado neste aparelho”.
- `WRITE_SETTINGS` é declarado no Manifesto para a API local de `pointer_speed`; não há tentativa especulativa de conceder permissões protegidas via `pm grant`.
- Renderers Automático, Vulkan, OpenGL ES, ANGLE e Skia permanecem visíveis, mas desabilitados para app externo sem API package-scoped segura. Não há renderer global, `setLayerType`, hook ou injeção de input.
- Foreground service `specialUse`, overlay, notificações, Shizuku provider e compatibilidade `minSdk 29`/`targetSdk 35` foram preservados.

## Verificações locais executadas

- `./gradlew --no-daemon --max-workers=1 clean :app:assembleDebug` — **PASS**
- `./gradlew --no-daemon --max-workers=1 :app:lintDebug` — **PASS** (warnings não bloqueantes)
- `aapt dump badging` — **PASS**; confirmou pacote, minSdk 29, targetSdk 35 e compileSdk 35
- `zipalign -f -p 16` — **PASS**; APK final revalidado com `zipalign -c -P 16 -v 4`
- `apksigner verify --verbose` — **PASS**; 1 signer, APK Signature Scheme v3
- `sha256sum` — **PASS**; hash acima
- Auditoria `javap` da API local `dev.rikka.shizuku:api:13.1.5` — **PASS**; assinaturas de `newProcess` e `ShizukuRemoteProcess` conferidas
- Busca estática de chamadas proibidas — **PASS**; ocorrências restantes são comentários/documentação ou `Thread.isAlive()` dos leitores de stream

## Crash real e validação física

**Não foi possível obter a exceção real do crash nem validar a ponte em aparelho nesta sessão.** Não há dispositivo/emulador conectado:

```text
adb devices -l
List of devices attached
```

Foram executados exatamente:

```bash
adb logcat -c
adb logcat AndroidRuntime:E *:S
adb shell echo SANTOS_SHIZUKU_OK
adb shell settings get system peak_refresh_rate
adb shell wm size
adb shell wm density
```

Resultado: `adb logcat` ficou em `- waiting for device -`; os quatro comandos retornaram `adb: no devices/emulators found`. Portanto:

- testes físicos executados: **nenhum**;
- stdout `SANTOS_SHIZUKU_OK` e exit code 0: **não confirmado**;
- sequência abrir sem Shizuku → iniciar → autorizar → testar ponte → ativar função: **não reproduzida fisicamente**;
- o APK é um build debug alinhado/assinado, mas **não deve ser declarado funcional no aparelho** até passar pelo Logcat e pelo primeiro echo em dispositivo real.
