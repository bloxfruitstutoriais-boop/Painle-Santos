# PAINEL SANTOS — entrega r7

## Arquivos

- APK debug: `PAINEL-SANTOS-debug.apk` r7
- Pacote: `painel.sensi.santos`
- `versionCode 8`, `versionName 1.0.7`
- `minSdk 29`, `targetSdk 35`, `compileSdk 35`
- SHA-256: `ead229c134b26064d9921491d22a332e67539ba17e09636ec520e4308e92b10f`

## Alteração principal desta revisão

- Detecção/autorização do Shizuku não executa mais teste, probe ou comando automaticamente.
- Somente **Testar ponte** inicia o processo remoto.
- Exceções do callback da UI agora são capturadas com `Log.e` e stack trace no diagnóstico, sem esconder a causa.
- Sem echo confirmado, os switches permanecem bloqueados.
- Onboarding removido; abertura direta com capa e um único indicador circular nativo.

## Build/verificação local

- `clean :app:assembleDebug` — PASS
- `:app:lintDebug` — PASS
- `aapt dump badging` — PASS
- `zipalign -f -p 16` e verificação — PASS
- `apksigner verify --verbose` — PASS, 1 signer, v3
- `sha256sum` — PASS

## Estado físico

**Não validado fisicamente.** O ambiente não possui aparelho/emulador ADB conectado. `adb logcat -c` e `adb logcat AndroidRuntime:E *:S` ficaram aguardando dispositivo; `echo SANTOS_SHIZUKU_OK`, `settings get system peak_refresh_rate`, `wm size` e `wm density` não puderam ser executados no firmware. A exceção real do crash também não foi obtida.

Não declarar a ponte como funcional até o primeiro teste físico retornar stdout contendo `SANTOS_SHIZUKU_OK` e exit code 0.

Detalhes completos: `VALIDACAO-2026-09-10.md`.
