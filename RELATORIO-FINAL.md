# Relatório final r7

A revisão r7 remove a execução automática ao detectar/iniciar o Shizuku. O painel abre sem executar comandos; somente o botão **Testar ponte** cria o processo remoto e inicia a validação. Sem echo confirmado, os switches ficam bloqueados.

O callback do diagnóstico agora registra qualquer exceção de UI com stack trace no Logcat e no diagnóstico, em vez de deixar o processo cair sem informação.

O onboarding de quatro páginas continua removido. A abertura mostra somente a capa Santos Team e um indicador circular nativo.

Build local final: `clean :app:assembleDebug` e `:app:lintDebug` passaram. APK `versionCode 8`, `versionName 1.0.7`, alinhado, assinado e verificado por `aapt`, `zipalign`, `apksigner` e SHA-256.

**Sem validação física:** não havia dispositivo/emulador ADB, portanto o crash real não pôde ser capturado e a ponte não pode ser declarada funcional no aparelho. Veja `VALIDACAO-2026-09-10.md`.
