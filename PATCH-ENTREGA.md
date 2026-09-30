# Patch final entregue — licença, reabertura da overlay e artes

## Arquivos alterados ou criados

- `app/src/main/java/painel/sensi/santos/MainActivity.java`
  - Reutiliza uma instância existente de `OverlayService` em vez de matá-la antes da nova abertura.
  - Impede duas chamadas de launch após retorno das permissões.
  - Envia `EXTRA_REQUEST_ID` e só aceita o `overlay_ready` da tentativa atual.
  - Para um serviço antigo quando não há key ou quando a key salva é rejeitada.

- `app/src/main/java/painel/sensi/santos/OverlayService.java`
  - `ACTION_OPEN` reexibe a bolha mesmo se o serviço já estiver vivo ou oculto na transmissão.
  - Fechar pelo `×` remove a janela, remove o foreground e encerra o serviço; não abre a Activity nem a tela da key.
  - Cancelamento de fechamento pendente impede a corrida que fazia uma abertura nova ser encerrada.

- `app/src/main/java/painel/sensi/santos/LicenseLockActivity.java`
  - Remove key, monitor e serviço antes de tentar abrir a tela de bloqueio; falha fechada mesmo se o Android impedir uma Activity iniciada em background.

- `app/src/main/res/drawable/santos_logo.jpg` e `app/src/main/res/drawable/floating_button.png`
  - A capa usa a imagem SANTOS TEAM fornecida pelo usuário.
  - A bolha usa a imagem da arma fornecida, independente da capa.

- `MainActivity.java`
  - As trocas das telas 1–4 e da tela da key mantêm a raiz opaca e não exibem um frame cinza.

- `OverlayService.java`
  - O status periódico do Shizuku também atualiza os estados dos controles; não é necessário reiniciar o app depois de autorizar.

- `PATCH-UI-CRASH-FIX.md`, `README.md`, `VALIDACAO-FINAL.md` e `AUDITORIA-SOLICITADO.md`
  - Atualizados para documentar o ciclo de vida e a separação das artes.

## Licenciamento já presente na fonte

- `app/src/main/kotlin/painel/sensi/santos/LicenseValidator.kt`
  - `POST https://painelsantoskey.lovable.app/api/public/keys/verify`
  - `x-api-key` em constante privada, sem logs.
  - Payload com `key` e `Settings.Secure.ANDROID_ID` em `device_id`.
  - `HttpURLConnection`, coroutine em `Dispatchers.IO`, timeout de conexão/leitura de 10 s.
  - Revalidação periódica; somente resposta explícita inválida/expirada/revogada encerra o painel. Erros transitórios preservam a sessão válida.

## Dependência

A fonte já possui em `app/build.gradle`:

```gradle
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1'
```

Não foi adicionado OkHttp.

## Build

```bash
export ANDROID_HOME=/caminho/para/android-sdk
./gradlew clean :app:assembleDebug
```

Nesta sessão o projeto passou por validação estática de XML e referências, mas o ambiente de execução não tinha Android SDK para gerar o APK nem dispositivo para teste interativo.
