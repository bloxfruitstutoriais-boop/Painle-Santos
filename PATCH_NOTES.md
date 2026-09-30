# Patch de manutenção — PAINEL SANTOS

## Interface e orientação

- Mantidas as três abas existentes: TOQUE, TELA e OTIMIZAÇÃO.
- Cada aba usa `ScrollView` vertical com `setFillViewport`, `setClipToPadding(false)`, nested scrolling e padding inferior de 30dp.
- O cabeçalho continua fixo e arrastável. O listener de arraste está somente no cabeçalho; controles e conteúdo não movem a janela.
- Painel expandido reduzido para aproximadamente 280dp de largura.
- Altura calculada com limites do display e barras de sistema: mínimo prático de 240dp e máximo de 620dp. A posição é limitada com margem e recalculada no callback de configuração.
- A bolha continua com 62dp, arrastável e iniciando minimizada.

## Arte e textos

- `santos_logo.png` é a arte fornecida da arma e é usada no splash e na bolha.
- A imagem é exibida com `CENTER_INSIDE` e máscara oval, sem redesenho, emoji ou deformação.
- O wallpaper black hole continua sendo o plano de fundo da Activity, com blur em Android 12+ e tint escuro.
- Onboarding atualizado para quatro textos explicativos.
- Página final do onboarding e aba de status mostram `Instagram: @davirosy2` e `TikTok: @davirosy2`.

## Stretch

- Slider `0–100%`, com fator `1,00x–1,33x`.
- `stretchedSize()` preserva o lado curto e reduz somente o lado maior, sem perfil retrato artificial.
- Exemplo: `1080x2400` → `1080x1800`; `2400x1080` → `1800x1080`.
- Adicionado switch independente **TELA CHEIA / CORTE LATERAL**, desligado por padrão.
- O slider não envia comando quando o switch está desligado; apenas salva o fator. A aplicação ocorre pelo switch ou pelo botão Aplicar.
- `wm size` e `wm density` são lidos pelo Shizuku; a densidade física real é usada sem multiplicação pelo fator.
- Estado original de size/density salvo antes da primeira alteração.
- Se size ou density falhar, rollback é tentado e a UI não marca a preferência como aplicada.
- Restaurar executa size e density separadamente e só remove o estado salvo quando ambos retornam exit code 0.

## Shell e Shizuku

- A API instalada é Shizuku 13.1.5. A ponte usa o processo remoto oficial `Shizuku.newProcess` porque essa versão não oferece método público `executeCommand`.
- `pingBinder()` e permissão são verificados antes de cada comando.
- stdout e stderr são drenados em streams separados, o processo é aguardado e somente exit code 0 é sucesso.
- Comando, stderr e exit code são registrados em `ShizukuBridge`/`ShellManager`.
- Cache e RAM agora são sequências separadas e só exibem conclusão após exit code 0.
- CPU/GPU/touch/network usam caminhos constantes e valores validados, guardam originais e restauram individualmente.
- Touch removido o fallback incorreto de `double_tap_enable`; somente `poll_rate` é elegível e o valor alvo precisa ser confirmado.
- Buffer TCP desfaz o primeiro parâmetro se o segundo falhar.
- Game Mode usa a interface oficial `cmd game mode performance` quando exposta e restaura o modo salvo.
- Doze usa `cmd deviceidle whitelist`, removendo apenas o pacote que o painel adicionou.
- Thermal ficou desabilitado: não executa `override-status`, `reset` nem bypass de proteção térmica.

## Estado e segurança

- Preferências são independentes; nenhuma desativação chama `SharedPreferences.clear()`.
- O serviço não reaplica recursos ao reiniciar. Switches podem refletir estado salvo, mas o comando não é executado automaticamente.
- A key foi preservada e não foi reimplementada.
- Não há mira/retícula, auto-aim, injeção de toque, uinput, sendevent, `/dev/input`, alteração de arquivos de jogos ou shell digitado pelo usuário.
