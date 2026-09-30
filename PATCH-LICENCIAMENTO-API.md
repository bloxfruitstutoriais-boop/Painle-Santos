# Patch final — licenciamento via API

## Arquivos modificados

- `app/build.gradle`
  - Adicionada a dependência `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`.
  - A rede continua usando `HttpURLConnection`; não foi adicionado OkHttp.
- `app/src/main/AndroidManifest.xml`
  - Registrada `LicenseLockActivity` como Activity interna (`exported=false`).
- `app/src/main/java/painel/sensi/santos/MainActivity.java`
  - O splash revalida uma key previamente salva antes de abrir o overlay.
  - A tela de key valida online antes de salvar a key e abrir o painel.
  - Após `valid=true`, inicia o monitor coroutine.
- `app/src/main/java/painel/sensi/santos/KeyValidator.java`
  - Removida a aceitação local de qualquer key; a classe agora só pode verificar se o campo não está vazio.
- `app/src/main/java/painel/sensi/santos/LicenseLockActivity.java`
  - Nova tela terminal. Para o `OverlayService`, remove a key salva e encerra a tarefa quando a licença falha.
- `app/src/main/kotlin/painel/sensi/santos/LicenseValidator.kt`
  - Novo validador assíncrono com `HttpURLConnection` e coroutine.

## Contrato usado

- `POST https://painelsantoskey.lovable.app/api/public/keys/verify`
- Header `x-api-key` fixo em constante privada, sem logs.
- JSON enviado: `{ "key": "...", "device_id": "ANDROID_ID" }`.
- Somente resposta JSON com `valid=true` e status HTTP 2xx abre o painel.

## Revalidação e falha transitória

- Após a ativação, o monitor consulta novamente a API em intervalo longo, sem exigir revalidação a cada poucos segundos.
- Somente resposta explícita de key inválida, expirada ou revogada abre `LicenseLockActivity`, encerra o serviço flutuante e remove a sessão/key salva.
- Timeout, DNS, conexão recusada, HTTP temporário e JSON malformado preservam uma sessão já validada e não abrem a tela de bloqueio.
- A sessão válida, um fingerprint não reversível da key e a data de expiração retornada pelo servidor são preservados localmente.
- Na próxima abertura, uma sessão local ainda dentro da expiração pode abrir o painel sem depender de uma nova consulta naquele instante.

## Build

```bash
JAVA_HOME=/caminho/para/jdk-17 ./gradlew clean :app:assembleDebug
```

O APK entregue nesta versão é `app/build/outputs/apk/debug/app-debug.apk`.
Não houve teste em aparelho Android físico nesta sessão; a resposta final da API depende da key real e do device id do aparelho.
