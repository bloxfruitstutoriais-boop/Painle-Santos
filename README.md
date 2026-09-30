# PAINEL SANTOS R9

Manutenção pontual do projeto Android nativo existente. Esta revisão é `versionCode 9` / `versionName 1.0.8` e inclui instrumentação de fluxo `DEBUG_FLOW`, cancelamento seguro da validação da key, diagnóstico reforçado da ponte Shizuku API 13.1.5 e o retorno explícito da overlay para a tela de key.

## O que foi corrigido

- Abertura sem as quatro telas/abas antigas: splash escuro com somente uma `ProgressBar`/bolinha indeterminada centralizada; o `ImageView` do logo não é adicionado ao splash; depois a tela de key. Instagram e TikTok permanecem no painel: `@davirosy2`.
- O Manifesto declara `WRITE_SETTINGS`; não existe botão/card/status adicional para essa permissão. `pointer_speed` verifica `Settings.System.canWrite()` somente no momento da alteração e informa a causa real se o Android recusar.
- Sliders de ponteiro, atraso de toque e escala de animação enviam explicitamente o valor solicitado, mesmo com o switch-mestre desligado; preferências só são salvas após confirmação do callback.
- O painel continua com as três abas TOQUE, TELA e OTIMIZAÇÃO.
- Conteúdo de cada aba está dentro de `ScrollView` com rolagem vertical, padding inferior e Nested Scrolling habilitado.
- Overlay expandido usa largura de aproximadamente 280dp e altura calculada pela área útil, com limite máximo de 520dp. O painel mantém o tema escuro, não usa contorno roxo forte e a posição é limitada para não ficar atrás das barras do sistema.
- O cabeçalho continua sendo a única área de arraste do painel expandido; switches, botões, sliders e ScrollViews não arrastam a janela.

## Stretch / TELA CHEIA / CORTE LATERAL

- O slider mantém o intervalo `0–100%` e calcula fator de `1,00x` a aproximadamente `1,33x`.
- O lado curto da resolução nativa é preservado; somente o lado maior é reduzido. Exemplos: `1080x2400` → aproximadamente `1080x1800`; `2400x1080` → aproximadamente `1800x1080`.
- A UI adiciona o switch separado **TELA CHEIA / CORTE LATERAL**, desligado por padrão. O slider sozinho apenas salva o fator; o comando de tela só é enviado quando o switch ou o botão Aplicar é usado.
- Antes de alterar, o app lê `wm size` e `wm density` pelo processo remoto do Shizuku e salva o override original (`reset` quando não havia override).
- A resolução física e a densidade física são lidas novamente pelo Shizuku e usadas para montar os perfis. O DPI aplicado não é multiplicado artificialmente pelo fator.
- O app registra fator, resolução, densidade e indicador de Corte lateral. Somente exit code 0 marca a alteração como aplicada.
- Se DPI ou resolução falhar, o estado anterior é restaurado e a UI não mostra sucesso. O firmware pode manter barras; o painel não promete remover o que o Android rejeitar.
- O botão **RESTAURAR resolução e DPI originais** executa rollback independente e informa falhas reais.

## Shizuku e comandos

A dependência instalada é Shizuku API `13.1.5`. Essa versão não expõe `Shizuku.executeCommand`; por isso `ShizukuBridge.executeCommand` encapsula o processo remoto oficial `Shizuku.newProcess`, sem `Runtime.exec`, `ProcessBuilder`, `su` ou shell local.

Cada execução:

1. verifica `Shizuku.pingBinder()`;
2. verifica a permissão binder;
3. usa comandos e valores allowlisted/validados;
4. lê stdout e stderr em threads separadas;
5. aguarda o processo e verifica exit code;
6. registra comando, stderr e falha no Logcat;
7. só confirma a preferência depois de sucesso.

Sem Shizuku o painel continua abrindo; recursos dependentes ficam desabilitados e nenhuma mensagem de sucesso é exibida.

## Funções funcionais e reversíveis

- Ponteiro: `0–10`.
- Atraso ao manter pressionado: `50–1000 ms`.
- Escala de animação: `0,0x–10,0x` em `window_animation_scale`, `transition_animation_scale` e `animator_duration_scale`.
- Não Perturbe: salva `zen_mode` e restaura o estado anterior.
- CPU: governor `performance` apenas quando o caminho exposto é legível/escrevível; restaura o governor salvo.
- GPU: usa somente caminhos conhecidos e graváveis expostos pelo driver; restaura a frequência original.
- Touch polling: somente caminhos `poll_rate` expostos pelo driver e confirmação do valor aplicado; o fallback de `double_tap` foi removido.
- Buffer TCP: lê os valores anteriores, aplica cada parâmetro separadamente e desfaz o primeiro se o segundo falhar.
- Game Mode: usa `cmd game mode performance` apenas quando o Android anuncia suporte e restaura o modo anterior.
- Doze Blocker: usa `cmd deviceidle whitelist` e remove somente o pacote adicionado pelo painel.
- Limpar cache e liberar RAM são ações pontuais: só exibem conclusão com exit code 0; quando a shell não possui privilégio, exibem o stderr/erro.
- Vulkan e OpenGL ES aparecem como capabilities reais do aparelho; Skia é o renderer padrão das Views do painel. Não existe API pública segura para forçar Vulkan/OpenGL/Skia em jogo externo, então essa opção não é falsificada como funcional.
- Thermal fica cinza/não suportado. O projeto não executa `override-status`, `reset` térmico ou qualquer bypass de proteção.

Cada recurso usa uma preferência independente. O serviço nunca reaplica automaticamente todos os recursos ao reiniciar; o estado visual salvo não dispara comandos.

## Fora do escopo e removido

Não há mira/retícula, auto-aim, injeção de toque, `uinput`, `sendevent`, `/dev/input`, alteração de arquivos de jogos, renderer global fictício, forçamento contínuo de CPU/núcleos, bypass térmico ou Wi-Fi fictício.

A key é verificada pela API antes de abrir o painel, a sessão válida e a expiração retornada são preservadas localmente, e a revalidação ocorre periodicamente sem bloquear por falha transitória; consulte `PATCH-LICENCIAMENTO-API.md`.

A reabertura pelo launcher/notificação é idempotente: `ACTION_OPEN` reutiliza uma instância viva quando existe ou cria outra quando o serviço anterior terminou. Fechar no X remove a janela, a notificação e o serviço e abre a tela de key, sem iniciar diretamente uma nova overlay.

A autorização do Shizuku é reavaliada enquanto o painel está aberto; os controles são habilitados sem exigir fechar e reabrir o aplicativo. As telas 1–4 e a tela da key não usam mais alpha 0 na raiz durante a troca de conteúdo, evitando o frame cinza em aparelhos que exibiam o fundo da janela.

## Build e limitações de validação

```bash
export JAVA_HOME=/caminho/para/jdk-17
./gradlew clean assembleDebug lintDebug
```

O APK gerado nesta revisão é debug e serve para teste. `assembleDebug`, `lintDebug`, `aapt dump badging`, `zipalign` e `apksigner verify` passaram; `WRITE_SETTINGS` aparece no Manifesto empacotado. Não houve aparelho/emulador ADB conectado: o primeiro `echo SANTOS_SHIZUKU_OK` e o fluxo real de crash ainda precisam ser executados fisicamente. Consulte `VALIDACAO-FINAL.md`.
