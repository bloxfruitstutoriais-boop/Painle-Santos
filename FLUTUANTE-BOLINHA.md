# Flutuante original — PAINEL SANTOS

A bola usada nesta versão é `app/src/main/res/drawable/floating_button.png`, a mesma imagem circular encontrada nos sources `r14` e `final`.

## Fluxo

- Ao iniciar o serviço, aparece a bola flutuante.
- Toque na bola: abre o painel completo.
- Toque no botão `−` do painel: recolhe novamente para a bola.
- Arraste a bola: muda sua posição dentro dos limites da tela.
- Toque no botão `×`: fecha o painel e encerra o fluxo normal da sessão.

A bola é uma `ImageView` de 54dp com `ScaleType.CENTER_CROP`, fundo arredondado e `ViewOutlineProvider` oval para manter o recorte circular. Não há AccessibilityService, janela invisível ou captura de toques globais.

Para aparecer, o Android precisa permitir **Sobrepor a outros apps** para o PAINEL SANTOS.
