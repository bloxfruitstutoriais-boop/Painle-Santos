# PAINEL SANTOS R11 — correção do travamento em TESTAR PONTE

## Falha reportada

Ao tocar em `TESTAR PONTE SHIZUKU`, a overlay aparentava travar.

## Causa encontrada

O clique fazia operações potencialmente bloqueantes na Main Thread antes de iniciar o trabalho remoto:

- `ShizukuBridge.hasPermission()`;
- `updateCapabilityStates()`;
- `message()`, que consultava `ShizukuBridge.status()`.

Além disso, o teste remoto executava quatro comandos sequenciais antes de devolver resposta: `echo`, `peak_refresh_rate`, `wm size` e `wm density`. Um `wm`/binder lento podia deixar o usuário sem retorno visual.

O polling periódico de status também consultava binder diretamente na Main Thread.

## Correção R11

- O clique não consulta mais binder/status de forma síncrona.
- `ShizukuBridge.testBridge()` inicia diretamente uma thread de trabalho.
- O teste de validação executa primeiro e somente:

```text
echo SANTOS_SHIZUKU_OK
```

- A ponte só é marcada como validada quando stdout contém `SANTOS_SHIZUKU_OK` e exit code é `0`.
- `peak_refresh_rate`, `wm size` e `wm density` não ficam no caminho do botão de teste.
- O status periódico usa `refreshStatusAsync()` em thread própria.
- `status()` e `hasPermission()` retornam snapshot quando chamados na Main Thread, sem IPC síncrono.
- O callback mostra imediatamente `testando`, `ponte validada` ou `falha na ponte`.
- stdout, stderr, exit code e exceção continuam registrados no diagnóstico.
- A execução remota continua usando exclusivamente `Shizuku.newProcess`/`ShizukuRemoteProcess`, com timeout e drenagem paralela.

## Build R11

```text
BUILD SUCCESSFUL
```

APK:

- pacote: `painel.sensi.santos`;
- versão do projeto: `versionCode 9`, `versionName 1.0.8`;
- minSdk 29;
- targetSdk 35;
- zipalign aprovado;
- assinatura debug v2 válida;
- SHA-256: `1c1e14aa6cdd9ed654364ef04f80c051bdd0846389febf20c1c556a6aa0f4b67`.

## Limitação

Não há aparelho ou ADB nesta sessão. Portanto, não afirmo que o travamento físico foi reproduzido ou eliminado em um firmware específico. O teste obrigatório no aparelho é:

```bash
adb logcat -c
adb logcat -v threadtime AndroidRuntime:E DEBUG_FLOW:D ShizukuBridge:E OverlayService:E '*:S'
```

Depois tocar `TESTAR PONTE SHIZUKU` e verificar no Logcat:

```text
SHIZUKU_ECHO_RESULT exit=0 stdout=SANTOS_SHIZUKU_OK
```
