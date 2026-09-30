# Revisão completa — PAINEL SANTOS r12

## Resultado

O projeto está pronto para gerar APK Debug e Release. A compilação foi executada neste workspace com:

- Gradle Wrapper 8.7
- Android Gradle Plugin 8.5.2
- Kotlin 1.9.24
- compileSdk/targetSdk 35
- minSdk 29
- Java/JVM target 17
- Shizuku API/provider 13.1.5
- kotlinx-coroutines-android 1.8.1

Não há Compose no projeto; portanto `kotlinCompilerExtensionVersion` não se aplica.

## Erros e pontos corrigidos

1. **Alto — ambiente sem Android SDK:** a primeira tentativa parou em `SDK location not found`, antes de compilar o código. O projeto não foi alterado por esse problema; o SDK Android 35 foi instalado no ambiente de validação e o build passou.
2. **Alto — referência estática forte ao Service:** `OverlayService.activeInstance` podia reter o Service e a hierarquia de Views. Corrigido em `app/src/main/java/painel/sensi/santos/OverlayService.java` com `WeakReference` e limpeza no `onDestroy()`.
3. **Médio — WRITE_SETTINGS sem caminho de concessão:** o slider `pointer_speed` apenas informava a falha. Corrigido para abrir `Settings.ACTION_MANAGE_WRITE_SETTINGS` com a URI do pacote, com tratamento de exceção e mensagem de fallback.
4. **Médio — buildTypes incompletos para release reproduzível:** `debug` não estava declarado explicitamente e não havia configuração de assinatura parametrizada. Corrigido em `app/build.gradle`: debug explícito, release explícito e `signingConfigs.release` condicional, alimentado por propriedades Gradle ou variáveis de ambiente.
5. **Baixo — warnings de parâmetros sem uso:** `ShellManager.setThermalOptimization` mantém a assinatura pública usada pelo Java, mas os parâmetros de contexto/estado não são necessários porque a operação é deliberadamente não suportada. Não é erro de compilação.
6. **Baixo — warnings de lint de UI/documentação:** há 88 warnings não bloqueantes agrupados em `SetTextI18n` (strings construídas programaticamente), `ObsoleteSdkInt` (guards mantidos por compatibilidade), `UnusedResources`, ícones bitmap densityless, orientação portrait intencional, `HardwareIds`, `DataExtractionRules`, `ViewConstructor`, `ClickableViewAccessibility`, `DrawAllocation` e APIs deprecated. Nenhum é erro de compilação ou recurso ausente.
7. **Alto — bloqueio funcional excessivo da UI:** o código exigia simultaneamente key com `dias >= 7` e o teste manual da ponte para habilitar praticamente todos os controles. Corrigido: key válida/não expirada mantém as configurações utilizáveis; autorização Shizuku é suficiente para executar; o teste manual ficou apenas como diagnóstico. Sem Shizuku, o controle pode ser tocado e mostra o erro real, em vez de parecer permanentemente desativado.
8. **Alto — sondagem remota durante a criação da janela:** após liberar os controles, a sondagem de capabilities e `wm size/density` podia iniciar dentro de `buildPanel()`/`onStartCommand()`, antes de `WindowManager.addView()`. Corrigido: a UI é montada primeiro; a sondagem só começa depois de `OVERLAY_WINDOW_ADDED`.
9. **Crítico — Shizuku no caminho de startup:** listeners e consultas de status eram preparados no `onCreate()`/abertura do serviço. Em aparelhos com Shizuku parado, binder instável ou provider incompatível, isso podia fazer a overlay cair antes de aparecer. Corrigido: startup sem chamadas ao binder/provider; listeners e sondagem só são registrados após o usuário tocar em `AUTORIZAR` ou `TESTAR PONTE`, com captura de `Throwable` e limpeza de listeners parciais.
10. **Alto — snapshot de autorização atrasado após conceder Shizuku:** switches, sliders, `wm size/density` e restauração podiam consultar `hasPermission()` na Main Thread antes de o snapshot visual ser atualizado. Corrigido: as operações não são canceladas pelo snapshot; cada worker consulta a autorização real imediatamente antes de criar/executar o processo remoto e devolve o erro real à interface.
11. **Crítico — concorrência ao ativar várias configurações:** `ShellManager` criava coroutines paralelas e a autorização disparava sondagens remotas automáticas de CPU/GPU/touch/display. Corrigido: dispatcher remoto de fila única; sondagens automáticas removidas; cada recurso só envia comando quando solicitado pelo usuário.

### Itens verificados sem erro
- Variáveis, métodos, classes e chamadas com tipos/argumentos compatíveis.
- Sem `TODO()`, `NotImplementedError` ou `UnsupportedOperationException` no código executável.
- Todos os `R.drawable` usados existem; layouts de spinner são corretamente `android.R.layout.*`.
- Sem referências a `R.string`, `R.id` ou layouts próprios inexistentes.
- Sem IDs duplicados: a UI é criada programaticamente.
- `MainActivity`, `LicenseLockActivity`, `OverlayService` e `OverlayControlReceiver` estão no Manifest.
- Provider do Shizuku está declarado com autoridade `${applicationId}.shizuku`.
- Launcher intent-filter está correto e `android:exported` está explícito.
- A sondagem remota inicial é adiada até depois de `WindowManager.addView()`, evitando que Shizuku bloqueie ou derrube a criação do painel.
- `POST_NOTIFICATIONS` é solicitada em runtime para API 33+.
- A autorização do Shizuku é explícita; comandos só são enviados após binder/permissão. O teste de ponte continua disponível como diagnóstico, mas não bloqueia artificialmente os controles.
- O startup da overlay não chama `Shizuku`, `ShizukuProvider` ou sondagem remota; listeners são registrados somente após ação explícita do usuário.
- Cada operação remota consulta binder/permissão dentro do worker que executará o comando; o snapshot visual da Main Thread não cancela mais resolução, DPI, sliders, switches ou restaurações imediatamente após autorização.
- O executor remoto usa dispatcher de fila única e `PROCESS_LOCK`; não há vários processos Shizuku simultâneos quando vários controles são ativados.
- Sondagens automáticas de capabilities/display foram removidas do pós-autorização; recursos são validados no próprio comando solicitado.
- Shell remoto usa `Shizuku.newProcess`; não há `Runtime.exec`, `ProcessBuilder`, `su` local ou NetworkOnMainThreadException.
- Callbacks retornam à Main Looper e têm guarda `destroyed` antes de tocar Views.
- Listeners do Shizuku são registrados sob demanda, removidos no `onDestroy()` e qualquer registro parcial é desfeito em caso de falha.
- A regra de expiração continua centralizada em `KeyManager`: `<= 0` expira e key ausente/inválida bloqueia. `diasRestantes` e `fullAccess` continuam visíveis como informação, mas não deixam uma key válida/não expirada com toda a UI desativada.
- `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `ACCESS_NOTIFICATION_POLICY`, `WRITE_SETTINGS` e a permissão do Shizuku estão declaradas.
- `PACKAGE_USAGE_STATS` e `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` não foram adicionadas: a leitura do app em primeiro plano e a whitelist Doze são feitas pelo shell autorizado do Shizuku, não por APIs diretas do aplicativo. Declarar permissões especiais sem uso não concede o privilégio e amplia a superfície de política/privacidade.

## Arquivos corrigidos nesta revisão final

- `app/build.gradle`
  - debug/release explícitos;
  - assinatura release segura por propriedades/ambiente;
  - comentários `// CORRIGIDO:` nos pontos alterados.
- `app/src/main/java/painel/sensi/santos/OverlayService.java`
  - referência estática fraca;
  - fluxo de autorização de `WRITE_SETTINGS`;
  - remoção do gate obrigatório de 7 dias para configurações;
  - remoção do gate obrigatório de teste manual da ponte;
  - controles de resolução/DPI/stretch/sliders clicáveis com key ativa;
  - startup sem consulta/registro automático do Shizuku;
  - listeners Shizuku sob demanda e protegidos contra binder/API instável;
  - bloqueios antecipados por snapshot removidos; autorização validada no worker;
  - fila única no executor Kotlin e sondagens automáticas removidas;
  - comentários `// CORRIGIDO:` nos pontos alterados.

- `app/src/main/kotlin/painel/sensi/santos/ShizukuManager.kt` e `ShellManager.kt`
  - dispatcher remoto com paralelismo 1;
  - nenhuma rajada de processos concorrentes ao ativar vários controles;
  - erros individuais retornam ao callback e preservam a overlay.

## Validações executadas

```bash
./gradlew --no-daemon --max-workers=1 clean :app:assembleDebug
./gradlew --no-daemon --max-workers=1 :app:lintDebug
./gradlew --no-daemon --max-workers=1 clean :app:assembleDebug :app:lintDebug :app:assembleRelease
```

Resultados:

- `assembleDebug`: **PASS**
- `lintDebug`: **PASS** — warnings não bloqueantes, zero erros
- `assembleRelease`: **PASS** — sem segredos gera `app-release-unsigned.apk`
- Release com keystore temporário externo: **PASS** — `apksigner verify` confirmou assinatura v2
- `aapt dump badging`: **PASS** — pacote `painel.sensi.santos`, min 29, target 35, compile 35
- `aapt dump permissions`: **PASS** — Manifest empacotado contém overlay, foreground service, notificações, `WRITE_SETTINGS`, rede, política DND e API Shizuku.
- `zipalign -c -P 16 -v 4`: **PASS**
- `apksigner verify --verbose`: **PASS** para o Debug
- classes duplicadas/recursos/manifest merger: **PASS**

APK Debug validado:

```text
app/build/outputs/apk/debug/app-debug.apk
SHA-256: 67bf10441aa686a95fec52da7f40b2b853822e8aae450e05190f1939a3c46e48
```

Não há dispositivo Android conectado neste ambiente; portanto Shizuku, overlay, API de licença e comportamento específico de firmware precisam ser exercitados no aparelho. Nesta versão, a abertura do painel foi deliberadamente desacoplada do Shizuku; a ponte só deve ser exercitada depois que a overlay estiver visível.
