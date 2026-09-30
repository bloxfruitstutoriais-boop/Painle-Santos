# Projeto para Sketchware Pro / Android Studio

Este ZIP contém o projeto completo do PAINEL SANTOS, com Java, Kotlin, Manifest, Gradle wrapper, recursos e a implementação centralizada de Shizuku.

## Importação

1. Extraia o ZIP sem criar `local.properties` manualmente.
2. Em Sketchware Pro, importe como projeto Android/Gradle compatível ou copie `app/src/main` para o projeto.
3. Em Android Studio, abra a pasta raiz e sincronize o Gradle.
4. Use JDK 17 ou 21 e compile com SDK 35.
5. Execute `./gradlew :app:assembleDebug`.

A dependência é `dev.rikka.shizuku:api:13.1.5` e `dev.rikka.shizuku:provider:13.1.5`.

## Ponte

- `ShizukuBridge.java`: processo remoto real, stdout/stderr, timeout, rollback e diagnóstico.
- `ShizukuManager.kt`: fachada com `checkPermission()`, `execute(command)` e `applyOptimization(command, callback)` em `Dispatchers.IO`.
- `ShellManager.kt`: operações serializadas e sondagem de capabilities do kernel.

Não há execução local, root local, injeção de input ou alteração de renderer de aplicativo externo.

## Limitação obrigatória

Este source foi compilado e verificado localmente, mas não foi instalado nem testado em aparelho nesta sessão. Antes de habilitar switches, execute no aparelho o primeiro teste `echo SANTOS_SHIZUKU_OK`; só aceite se stdout contiver o marcador e exit code for 0. Consulte `VALIDACAO-2026-09-10.md`.
