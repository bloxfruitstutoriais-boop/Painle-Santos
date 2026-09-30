# Análise dos dois sources enviados

Os dois ZIPs enviados são o mesmo source: os arquivos Java, Kotlin, recursos e
manifests têm o mesmo conteúdo. Portanto, trocar de um ZIP para o outro não
mudaria o comportamento.

## Problemas encontrados

1. A ponte do Shizuku dependia de processos remotos concorrentes. A leitura de
   display, restauração e ações dos switches podiam abrir várias shells ao
   mesmo tempo.
2. `readRawDisplayState()` executava `wm overscan` em toda leitura, embora o
   comando não exista em muitos Androids recentes. Isso aumentava muito o
   tempo de resposta e podia deixar processos remotos presos.
3. O polling de estado podia tentar novamente uma leitura pesada logo após uma
   falha, sem backoff.
4. A fila do `ShellManager` era um cached thread pool; toques sucessivos em
   switches podiam disparar operações simultâneas.
5. A leitura de display bem-sucedida não liberava explicitamente o marcador de
   requisição em todos os caminhos.
6. O relatório antigo declarava build e validação final, mas também registrava
   que não havia aparelho conectado. Isso não prova que o caminho de Shizuku
   funciona no firmware do usuário; o diagnóstico definitivo exige Logcat.

## Correções aplicadas neste source

- Processos remotos do Shizuku serializados por lock.
- `ShellManager` alterado para fila de uma operação por vez.
- `wm overscan` removido da sondagem automática de resolução/DPI.
- Backoff de 10 segundos após falha de leitura de display.
- Estado da leitura de display liberado também no caminho de sucesso.
- Prompt de reparo incluído em `PROMPT-IA-REPARO.md`.

Nenhuma função de firmware é declarada universal. O status da overlay deve
mostrar o exit code real quando um driver ou comando não for suportado.