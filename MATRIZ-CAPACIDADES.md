# Matriz de capacidades — PAINEL SANTOS R10

A coluna “Funcionalidade física” não é marcada como confirmada nesta entrega: não houve aparelho/ADB nesta sessão.

| Função | Caminho | Precisa Shizuku? | Precisa WRITE_SETTINGS? | Como confirmar | Estado nesta entrega |
|---|---|---:|---:|---|---|
| `pointer_speed` | API local `Settings.System.getInt/putInt` | Não | Sim, quando `Settings.System.canWrite()` exigir | Reler `Settings.System.pointer_speed`; também `adb shell settings get system pointer_speed` | Caminho separado implementado; físico pendente |
| `long_press_timeout` | `settings put/get secure long_press_timeout` via `Shizuku.newProcess` | Sim | Não como pré-requisito global | Reler o setting pelo mesmo caminho remoto; conferir stdout/stderr/exit code | Caminho remoto preservado; físico pendente |
| escalas de animação | `settings put/get global window_animation_scale`, `transition_animation_scale`, `animator_duration_scale` via Shizuku | Sim | Não como pré-requisito global | Reler cada escala e exigir exit code 0; rollback em falha | Caminho remoto preservado; físico pendente |
| CPU governor | leitura/escrita de governor em sysfs por `ShellManager` remoto | Sim | Não | Ler o mesmo arquivo após escrita; rollback ao original | Capability/rollback existentes; driver físico pendente |
| GPU | leitura/escrita de `min_freq`/`max_freq` no driver exposto | Sim | Não | Reler frequência aplicada e comparar com o limite exposto | Capability/rollback existentes; driver físico pendente |
| touch polling | leitura/escrita de `poll_rate`/`report_rate` exposto pelo driver | Sim | Não | Reler o arquivo e exigir o valor confirmado | Capability/rollback existentes; driver físico pendente |
| TCP buffer | `sysctl -n/-w net.ipv4.tcp_rmem/tcp_wmem` remoto | Sim | Não | Reler os dois triples; desfazer o primeiro se o segundo falhar | Caminho remoto preservado; kernel físico pendente |
| Game Mode | `cmd game mode` remoto, somente se o Android anunciar suporte | Sim | Não | Ler modo anterior, aplicar e reler/observar exit code 0; restaurar modo anterior | Caminho remoto preservado; API/firmware pendente |
| Doze | `cmd deviceidle whitelist` remoto | Sim | Não | Ler whitelist antes/depois; remover somente o pacote adicionado | Caminho remoto preservado; firmware pendente |
| `wm size` | `wm size` via `Shizuku.newProcess` | Sim | Não | Reler `wm size`, aceitar orientação equivalente e verificar rollback | Caminho remoto preservado; físico pendente |
| `wm density` | `wm density` via `Shizuku.newProcess` | Sim | Não | Reler `wm density` e confirmar DPI efetivo | Caminho remoto preservado; físico pendente |
| refresh rate | Display API para modos expostos + settings oficiais via `ShellManager`/Shizuku | Sim para aplicar globalmente | Não como pré-requisito global | Confirmar settings e/ou taxa efetiva do `Display`; rollback | Caminho remoto preservado; físico pendente |
| funções locais da overlay | `WindowManager`, `View`, `Choreographer` e estado local do serviço | Não | Não | `windowAdded`, `isAttachedToWindow`, callback do `WindowManager` | Independente de Shizuku; físico pendente |
| cache/RAM | `drop_caches`/`am kill-all` remoto | Sim | Não | stdout/stderr e exit code 0; não exibir sucesso em erro | Caminho remoto preservado; físico pendente |

## Regra de estado

- “Aplicando…” é exibido durante a operação.
- Só a função em operação é bloqueada; controles independentes permanecem utilizáveis quando sua própria capacidade está disponível.
- Preferência e estado visual só são atualizados após confirmação real.
- Falhas retornam a causa, stdout/stderr/exit code ou exceção disponível; não são convertidas em “não suportado” genericamente.
