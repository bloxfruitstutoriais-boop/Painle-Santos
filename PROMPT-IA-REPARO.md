# Prompt para outra IA — reparar o PAINEL SANTOS sem inventar correções

Você é responsável por entregar uma correção real de um aplicativo Android
Java/Kotlin que usa Shizuku 13.1.5, `WindowManager` para uma bolha flutuante e
uma API online para validar uma key.

## Regra principal

Não diga que está corrigido sem compilar o projeto, revisar o fluxo completo e
mostrar os erros reais do Logcat quando existir um aparelho conectado. Não
invente APIs do Shizuku, comandos Android ou suporte universal de firmware.
Quando uma função não for suportada pelo aparelho, ela deve mostrar uma falha
explicada, desfazer alteração parcial quando possível e nunca derrubar o app.

## Problema que precisa ser resolvido

Sem Shizuku, o app abre e a validação da key funciona. Depois que o usuário
inicia e autoriza o Shizuku:

1. os controles que dependem do Shizuku precisam ser habilitados sem reiniciar
   o app;
2. cada comando deve usar somente a ponte oficial do Shizuku;
3. a bolha não pode desaparecer nem causar `WindowManager.BadTokenException`,
   `IllegalArgumentException`, `RemoteServiceException` ou crash em callback;
4. fechar no X precisa encerrar a janela e abrir a tela da key novamente;
5. reabrir a bolha precisa reutilizar uma instância válida do serviço ou criar
   uma nova, nunca chamar `addView` duas vezes nem `updateViewLayout` numa View
   removida;
6. se o usuário abrir o app enquanto o serviço ainda está terminando, o fluxo
   precisa esperar o estado real e permitir retry;
7. qualquer falha do Shizuku deve aparecer no status com o exit code e parte de
   stdout/stderr, sem lançar a exceção para a UI;
8. nenhuma thread de trabalho pode tocar `View`, `TextView`, `Switch`, `Spinner`
   ou `SeekBar` fora da Main Looper;
9. callbacks recebidos depois de `onDestroy()` devem ser ignorados;
10. uma shell remota travada precisa ter timeout, destruição e fechamento dos
    streams;
11. comandos não suportados, como recursos específicos de `wm`, sysfs,
    `cmd game` ou thermal, devem ser detectados pelo exit code e não tratados
    como sucesso;
12. preservar a validação da key, a imagem `santos_logo.jpg` e a imagem
    `floating_button.png`.

## Shizuku obrigatório

O projeto usa:

```gradle
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'
```

Na API 13.1.5 não existe `Shizuku.executeCommand`. A execução de shell deve
usar a assinatura privada real `newProcess(String[], String[], String)` via
reflexão cuidadosamente isolada, ou uma alternativa oficial comprovadamente
compatível com essa versão. Antes de executar:

- verificar `Shizuku.pingBinder()`;
- verificar `Shizuku.checkSelfPermission()`;
- capturar `NoSuchMethodException`, `IllegalAccessException`,
  `InvocationTargetException`, `SecurityException`, `RemoteException` e
  qualquer erro do processo;
- serializar processos remotos para não abrir várias shells concorrentes;
- drenar stdout e stderr em paralelo;
- aplicar timeout;
- registrar comando allowlisted, exit code e saída limitada;
- nunca usar `Runtime.exec`, `ProcessBuilder`, `su` local ou shell local como
  fallback.

## Checklist de implementação

### Serviço e overlay

- Criar o notification channel antes de `startForeground`.
- Usar o tipo de foreground service que corresponde ao manifesto.
- Verificar `Settings.canDrawOverlays()` antes de `WindowManager.addView`.
- Manter uma flag própria `windowAdded`.
- `remove()` deve marcar a flag antes de remover e tolerar uma View já removida.
- `resizePanel()` só pode chamar `updateViewLayout` quando a View estiver
  anexada.
- `onDestroy()` deve cancelar callbacks, animações, medição de FPS e remover a
  View com segurança.
- `onStartCommand()` deve tratar `OPEN`, `SHOW`, `HIDE` e `STOP` sem duplicar
  janela ou foreground service.
- Em falha, salvar a classe e a mensagem da exceção para diagnóstico.

### Permissão e estado

- Atualizar controles quando a permissão do Shizuku for concedida.
- Não sondar comandos pesados a cada ciclo de polling.
- Aplicar backoff para uma capability que falhou.
- Não chamar `wm overscan` em toda leitura; muitos firmwares atuais não
  suportam esse comando.
- Nunca salvar um estado original inválido ou apagar a key por causa de uma
  falha de shell.

### UI

- Toda alteração visual deve ocorrer na Main Looper.
- Nenhuma transição pode deixar a raiz com alpha zero sobre um fundo cinza.
- Ao fechar o painel, abrir a key sem criar um loop de Activity.
- Se a key for válida, só finalizar a Activity depois que a overlay confirmar
  `addView` concluído.

## Testes obrigatórios antes da entrega

1. `./gradlew clean assembleDebug`
2. `./gradlew lintDebug`
3. instalar APK em Android 12, 13, 14 ou 15 quando disponível;
4. iniciar sem Shizuku;
5. iniciar Shizuku, autorizar e observar a habilitação dos controles;
6. executar uma função suportada e uma não suportada;
7. fechar no X, reabrir, minimizar e mostrar novamente pela notificação;
8. revogar a autorização do Shizuku durante o painel aberto;
9. matar o processo e abrir novamente;
10. capturar:

```bash
adb logcat -c
adb logcat -v threadtime -s AndroidRuntime ShizukuBridge OverlayService ShellManager
adb logcat -b crash -d -v threadtime
```

Se não houver dispositivo, declarar explicitamente que o teste físico não foi
feito. Entregar o source e o APK somente quando o build realmente produzir
`app/build/outputs/apk/debug/app-debug.apk`.