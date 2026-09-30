# PAINEL SANTOS R12 — integração Shizuku

## Falhas tratadas

- Estado “Shizuku indisponível” baseado em snapshot inicial falso antes da consulta real do binder.
- Botão Autorizar consultando disponibilidade na Main Thread.
- Binder podendo ser entregue depois da criação da overlay sem listener para atualizar o estado.
- Teste da ponte e funções dependentes permanecendo bloqueados enquanto o binder ainda não tinha sido recebido.

## Alterações

- `ShizukuProvider.requestBinderForNonProviderProcess()` é solicitado em thread própria durante a sondagem de status.
- `Shizuku.addBinderReceivedListenerSticky()` atualiza o serviço assim que o binder chega.
- O botão Autorizar primeiro executa uma sondagem real em background; só abre o Manager quando o binder realmente não responde.
- `status()`/`hasPermission()` não fazem IPC síncrono na Main Thread; a UI usa snapshot.
- `TESTAR PONTE SHIZUKU` executa somente o primeiro teste obrigatório:

```text
echo SANTOS_SHIZUKU_OK
```

- A validação exige stdout com `SANTOS_SHIZUKU_OK` e exit code `0`.
- stdout, stderr, exit code e exceção continuam no Logcat/diagnóstico.
- Todos os comandos remotos continuam em `Shizuku.newProcess`/`ShizukuRemoteProcess`; não há `Runtime.exec`, `ProcessBuilder`, `su` local ou shell local.

## Build R12

```text
BUILD SUCCESSFUL
```

Validações:

- pacote `painel.sensi.santos`;
- minSdk 29;
- targetSdk 35;
- `WRITE_SETTINGS` presente;
- zipalign aprovado;
- assinatura debug v2 válida.

Não há aparelho/ADB nesta sessão. O funcionamento físico do Shizuku e dos drivers ainda precisa ser confirmado no aparelho do usuário com Logcat.
