package painel.sensi.santos

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant // CORRIGIDO BUG2: datas da API são convertidas para epoch UTC.
import java.time.LocalDate // CORRIGIDO BUG2: aceita datas sem horário em formato de calendário.
import java.time.LocalDateTime // CORRIGIDO BUG2: aceita respostas sem offset tratando-as como UTC.
import java.time.ZoneOffset // CORRIGIDO BUG2: impede que o timezone local altere os dias restantes.
import java.time.format.DateTimeFormatter // CORRIGIDO BUG2: suporta ISO e formatos legados de expiração.
import java.time.format.DateTimeParseException // CORRIGIDO BUG2: trata data inválida sem crash.
import java.util.concurrent.CancellationException
import java.lang.ref.WeakReference

/** Verificação online da licença, sem tocar a UI fora da Main Thread. */
object LicenseValidator {
    private const val TAG = "LicenseValidator"
    private const val FLOW_TAG = "DEBUG_FLOW"
    private const val VERIFY_URL = "https://painelsantoskey.lovable.app/api/public/keys/verify"
    private const val API_HEADER = "x-api-key"
    private const val API_KEY = "SANTOSAPI_52YYZ8GYV23LZ4W7QRN4FVKYGA5UMSWWQ2NCU72K"
    // A sessão válida não precisa de uma nova consulta a cada poucos segundos.
    private const val POLL_INTERVAL_MS = 15 * 60 * 1_000L
    private const val SESSION_VALID = "license_session_valid"
    private const val SESSION_KEY_HASH = "license_key_hash"
    private const val SESSION_EXPIRES_AT = "license_expires_at"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_RESPONSE_CHARS = 64 * 1024

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var monitorJob: Job? = null

    /** Resultado sem dados sensíveis; nunca contém a key nem o device id. */
    class Result(
        @JvmField val valid: Boolean,
        // Mantido por compatibilidade com o fluxo existente; em falha de rede
        // significa erro transitório, não revogação.
        @JvmField val criticalError: Boolean,
        @JvmField val message: String,
        @JvmField val definitiveInvalid: Boolean = false,
        @JvmField val expiresAtMillis: Long = 0L, // CORRIGIDO BUG2: mantém a expiração normalizada em epoch UTC.
        @JvmField val daysRemaining: Long? = null // CORRIGIDO BUG2: devolve os dias calculados para a UI e não um valor nulo/antigo.
    ) // CORRIGIDO BUG2: fecha o modelo de resultado com a informação de dias.

    interface Callback { fun onResult(result: Result) }

    /** Handle explícito: o dono cancela no onDestroy para não reter Activity. */
    class Request internal constructor(private val job: Job) {
        @Volatile private var cancelled = false
        fun cancel() {
            cancelled = true
            job.cancel()
            Log.d(FLOW_TAG, "KEY_REQUEST_CANCELLED") // LOG ADICIONADO
        }
        fun isCancelled(): Boolean = cancelled || !job.isActive
    }

    /** Executa uma verificação fora da UI e devolve o resultado na Main Thread. */
    @JvmStatic
    fun verifyOnce(context: Context, key: String, callback: Callback): Request {
        val appContext = context.applicationContext
        // WeakReference evita que uma callback esquecida retenha a Activity.
        val callbackRef = WeakReference(callback)
        Log.d(FLOW_TAG, "KEY_VERIFY_START") // LOG ADICIONADO
        val job = scope.launch {
            val result = try {
                withContext(Dispatchers.IO) { verifyBlocking(appContext, key.trim()) }
            } catch (cancelled: CancellationException) {
                Log.d(FLOW_TAG, "KEY_VERIFY_CANCELLED") // LOG ADICIONADO
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Falha não tratada no worker de licença", error)
                Result(false, true, "Não foi possível verificar a licença online.")
            }
            if (!isActive) return@launch
            if (result.valid) saveLocalSession(appContext, key.trim(), result.expiresAtMillis) // CORRIGIDO BUG2: salva a expiração nova e limpa cache antigo quando ausente.
            // scope usa Main.immediate, e este withContext deixa a garantia explícita.
            withContext(Dispatchers.Main.immediate) {
                val target = callbackRef.get()
                if (target == null) {
                    Log.d(FLOW_TAG, "KEY_CALLBACK_DROPPED_OWNER_GONE") // LOG ADICIONADO
                    return@withContext
                }
                try {
                    Log.d(FLOW_TAG, "KEY_RESULT valid=${result.valid} transient=${result.criticalError} invalid=${result.definitiveInvalid} expires=${result.expiresAtMillis > 0}") // LOG ADICIONADO
                    target.onResult(result)
                } catch (error: Exception) {
                    Log.e(TAG, "Callback da licença lançou exceção", error)
                }
            }
        }
        return Request(job)
    }

    /**
     * Revalida a licença em intervalo longo. Uma falha de rede, DNS, timeout,
     * HTTP temporário ou resposta malformada preserva a sessão já validada.
     * Somente uma resposta explícita de inválida/expirada/revogada bloqueia.
     */
    @JvmStatic
    fun startMonitoring(context: Context, key: String) {
        val normalizedKey = key.trim()
        stopMonitoring()
        if (normalizedKey.isEmpty()) {
            Log.d(FLOW_TAG, "KEY_MONITOR_NOT_STARTED_EMPTY") // LOG ADICIONADO
            return
        }
        val appContext = context.applicationContext
        monitorJob = scope.launch {
            Log.d(FLOW_TAG, "KEY_MONITOR_STARTED intervalMs=$POLL_INTERVAL_MS") // LOG ADICIONADO
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                if (!isActive) break
                val result = try {
                    withContext(Dispatchers.IO) { verifyBlocking(appContext, normalizedKey) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "Falha no monitor de licença", error)
                    Result(false, true, "Não foi possível verificar a licença online.")
                }
                if (!isActive) break
                if (result.valid) {
                    saveLocalSession(appContext, normalizedKey, result.expiresAtMillis)
                    Log.d(FLOW_TAG, "KEY_MONITOR_RESULT valid=true expires=${result.expiresAtMillis > 0}")
                    continue
                }
                if (result.definitiveInvalid) {
                    Log.d(FLOW_TAG, "KEY_MONITOR_EXPLICIT_INVALID_STOP")
                    LicenseLockActivity.open(appContext, result.message)
                    break
                }
                // A expiração já retornada pelo servidor é uma decisão local
                // independente de uma falha de rede transitória.
                val localExpiry = localSessionExpiry(appContext)
                if (localExpiry > 0L && localExpiry <= System.currentTimeMillis()) {
                    Log.d(FLOW_TAG, "KEY_MONITOR_LOCAL_SESSION_EXPIRED")
                    LicenseLockActivity.open(appContext, "A sessão local da licença expirou.")
                    break
                }
                // Timeout/DNS/HTTP temporário/JSON inválido não revoga uma key
                // que ainda está dentro da expiração local e não abre LockActivity.
                Log.w(FLOW_TAG, "KEY_MONITOR_TRANSIENT_KEEP_SESSION expires="
                        + (localExpiry <= 0L || localExpiry > System.currentTimeMillis()));
            }
            Log.d(FLOW_TAG, "KEY_MONITOR_FINISHED") // LOG ADICIONADO
        }
    }

    @JvmStatic // CORRIGIDO BUG2: consulta a mesma decisão de dias usada pelo painel.
    fun hasValidLocalSession(context: Context, key: String): Boolean { // CORRIGIDO BUG2: não mantém cache antigo após expiração.
        return KeyManager.hasValidSession(context, key) // CORRIGIDO BUG2: validade e expiração são avaliadas centralmente.
    }

    @JvmStatic
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        Log.d(FLOW_TAG, "KEY_MONITOR_STOPPED") // LOG ADICIONADO
    }

    private fun verifyBlocking(context: Context, key: String): Result {
        if (key.isEmpty()) return Result(false, false, "Digite uma key de acesso.")
        val deviceId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                .orEmpty().ifBlank { "unknown-device" }
        } catch (error: SecurityException) {
            Log.e(TAG, "Não foi possível ler ANDROID_ID", error)
            return Result(false, true, "Não foi possível identificar este aparelho.")
        }

        var connection: HttpURLConnection? = null
        return try {
            val body = JSONObject().put("key", key).put("device_id", deviceId).toString()
            connection = (URL(VERIFY_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty(API_HEADER, API_KEY)
            }
            connection.outputStream.use { output ->
                output.write(body.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
            val statusCode = connection.responseCode
            val responseBody = readResponse(
                if (statusCode in 200..299) connection.inputStream else connection.errorStream
            )
            Log.d(FLOW_TAG, "KEY_HTTP_RESPONSE status=$statusCode bodyChars=${responseBody.length}") // LOG ADICIONADO
            val response = parseResponse(responseBody)
            when {
                statusCode == 408 || statusCode == 429 || statusCode >= 500 ->
                    Result(false, true, "Servidor de licença indisponível temporariamente.")
                response?.valid == true && statusCode in 200..299 -> { // CORRIGIDO BUG2: calcula dias antes de liberar funções.
                    val access = KeyManager.accessFromExpiration(true, response.expiresAtMillis) // CORRIGIDO BUG2: usa a regra única UTC >=7/<7/<=0.
                    if (access.expired) { // CORRIGIDO BUG2: uma resposta válida mas já expirada não abre o painel.
                        Result(false, false, "Key expirada: acesso bloqueado.", true, response.expiresAtMillis ?: 0L, access.daysRemaining) // CORRIGIDO BUG2: reporta expiração sem deixar a UI interpretar como trial.
                    } else { // CORRIGIDO BUG2: data ausente segue como trial, sem usar zero como expiração.
                        Result(true, false, "Licença validada.", false, response.expiresAtMillis ?: 0L, access.daysRemaining) // CORRIGIDO BUG2: devolve os dias calculados.
                    } // CORRIGIDO BUG2: fecha decisão de validade positiva.
                } // CORRIGIDO BUG2: fecha cálculo de acesso para resposta válida.
                response?.definitiveInvalid == true ->
                    Result(false, false, "Key inválida, expirada ou revogada.", true)
                else -> Result(false, true, "Resposta inválida ou temporária do servidor de licença.")
            }
        } catch (error: SocketTimeoutException) {
            Log.e(TAG, "Timeout verificando licença", error)
            Result(false, true, "Tempo esgotado ao verificar a licença.")
        } catch (error: UnknownHostException) {
            Log.e(TAG, "Sem internet/DNS na verificação de licença", error)
            Result(false, true, "Sem internet para verificar a licença.")
        } catch (error: ConnectException) {
            Log.e(TAG, "Servidor de licença recusou a conexão", error)
            Result(false, true, "Servidor de licença indisponível.")
        } catch (error: IOException) {
            Log.e(TAG, "Erro de rede na verificação de licença", error)
            Result(false, true, "Falha de rede ao verificar a licença.")
        } catch (error: Exception) {
            Log.e(TAG, "Erro ao interpretar/verificar a licença", error)
            Result(false, true, "Não foi possível verificar a licença online.")
        } finally {
            connection?.disconnect()
        }
    }

    private data class ParsedResponse( // CORRIGIDO BUG2: mantém a resposta parseada separada da decisão de acesso.
        val valid: Boolean?, // CORRIGIDO BUG2: estado retornado pelo servidor.
        val definitiveInvalid: Boolean, // CORRIGIDO BUG2: invalidação explícita da API.
        val expiresAtMillis: Long? // CORRIGIDO BUG2: data expirada convertida para epoch UTC.
    )

    private fun parseResponse(body: String): ParsedResponse? {
        if (body.isBlank()) return null
        return try {
            val json = JSONObject(body)
            val valid = if (!json.has("valid")) null else when (val value = json.opt("valid")) {
                is Boolean -> value
                is String -> when {
                    value.equals("true", ignoreCase = true) -> true
                    value.equals("false", ignoreCase = true) -> false
                    else -> null
                }
                else -> null
            }
            val stateText = listOf("status", "state", "reason", "message")
                .mapNotNull { key -> if (json.has(key)) json.optString(key, "") else null }
                .joinToString(" ")
                .lowercase()
            val stateInvalid = stateText.contains("invalid")
                    || stateText.contains("expired")
                    || stateText.contains("revoked")
                    || stateText.contains("expirada")
                    || stateText.contains("revogada")
                    || stateText.contains("inválida")
                    || stateText.contains("invalida")
            ParsedResponse(valid, valid == false || stateInvalid,
                findExpiration(json, 0))
        } catch (error: Exception) {
            Log.e(TAG, "JSON de licença inválido", error)
            null
        }
    }

    private val expirationKeys = arrayOf(
        "expires_at", "expiresAt", "expiration", "expiration_date", "expires",
        "valid_until", "validUntil", "expires_on"
    )

    private fun findExpiration(json: JSONObject, depth: Int): Long? {
        if (depth > 2) return null
        for (key in expirationKeys) {
            if (json.has(key)) parseExpiration(json.opt(key))?.let { return it }
        }
        for (key in arrayOf("data", "license", "key", "result")) {
            val nested = json.optJSONObject(key) ?: continue
            findExpiration(nested, depth + 1)?.let { return it }
        }
        return null
    }

    private fun parseExpiration(value: Any?): Long? { // CORRIGIDO BUG2: parseia todas as datas aceitas em UTC.
        val raw = when (value) { // CORRIGIDO BUG2: preserva compatibilidade com epoch numérico.
            is Number -> value.toLong() // CORRIGIDO BUG2: aceita epoch em segundos ou milissegundos.
            is String -> value.trim().toLongOrNull() // CORRIGIDO BUG2: aceita epoch textual.
            else -> null // CORRIGIDO BUG2: null/JSONObject.NULL não vira data zero.
        } // CORRIGIDO BUG2: finaliza a leitura numérica.
        if (raw != null && raw > 0L) { // CORRIGIDO BUG2: ignora valores numéricos inválidos.
            return if (raw < 100_000_000_000L) raw * 1_000L else raw // CORRIGIDO BUG2: normaliza segundos para milissegundos UTC.
        } // CORRIGIDO BUG2: segue para formatos de texto.
        if (value !is String || value.isBlank()) return null // CORRIGIDO BUG2: data ausente será tratada como trial.
        val text = value.trim() // CORRIGIDO BUG2: remove espaços que causariam parse inválido.
        try { return Instant.parse(text).toEpochMilli() } // CORRIGIDO BUG2: prioriza ISO-8601 com timezone explícito.
        catch (_: DateTimeParseException) { /* tenta os formatos abaixo */ } // CORRIGIDO BUG2: não deixa data inválida causar crash.
        val dateTimeFormats = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss") // CORRIGIDO BUG2: aceita horário sem offset e interpreta UTC.
        for (pattern in dateTimeFormats) { // CORRIGIDO BUG2: percorre formatos legados de data/hora.
            try { return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern)).atOffset(ZoneOffset.UTC).toInstant().toEpochMilli() } // CORRIGIDO BUG2: converte data/hora para UTC.
            catch (_: DateTimeParseException) { /* próximo formato */ } // CORRIGIDO BUG2: tenta o próximo formato sem bloquear a key.
        } // CORRIGIDO BUG2: finaliza tentativa de data/hora.
        val dateFormats = listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd") // CORRIGIDO BUG2: suporta formatos PT-BR, legado EUA e ISO sem hora.
        for (pattern in dateFormats) { // CORRIGIDO BUG2: percorre formatos de calendário conhecidos.
            try { return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() } // CORRIGIDO BUG2: não usa timezone do aparelho.
            catch (_: DateTimeParseException) { /* data inválida neste padrão */ } // CORRIGIDO BUG2: retorna null somente após todas as tentativas.
        } // CORRIGIDO BUG2: finaliza o parse de data sem hora.
        Log.w(TAG, "Data de expiração inválida recebida pelo servidor") // CORRIGIDO BUG2: diagnostica resposta inválida sem expor a key.
        return null // CORRIGIDO BUG2: data inválida vira trial, não dias=0.
    }

    private fun saveLocalSession(context: Context, key: String, expiresAtMillis: Long) { // CORRIGIDO BUG2: delega o cache sem preservar expiração antiga.
        KeyManager.saveValidSession(context, key, expiresAtMillis.takeIf { it > 0L }) // CORRIGIDO BUG2: uma resposta sem data limpa a validade antiga e vira trial.
    }

    @JvmStatic // CORRIGIDO BUG2: expõe o snapshot recalculado ao painel flutuante.
    fun currentAccess(context: Context, key: String): LicenseKeyInfo = KeyManager.getAccessInfo(context, key) // CORRIGIDO BUG2: a UI sempre recebe dias UTC atuais.

    @JvmStatic // CORRIGIDO BUG2: botão Revalidar key limpa cache explicitamente.
    fun clearValidationCache(context: Context) = KeyManager.clearValidationCache(context) // CORRIGIDO BUG2: remove dias/expiração antigos antes da nova consulta.

    @JvmStatic // CORRIGIDO BUG2: permite ao painel revalidar a key ativa mesmo sem persistência opcional.
    fun revalidateKey(context: Context, key: String, callback: Callback): Request { // CORRIGIDO BUG2: limpa cache e valida a key informada.
        val appContext = context.applicationContext // CORRIGIDO BUG2: não mantém Activity/Service como contexto de rede.
        KeyManager.clearValidationCache(appContext) // CORRIGIDO BUG2: impede resultado antigo de continuar liberando funções.
        return verifyOnce(appContext, key.trim(), callback) // CORRIGIDO BUG2: valida novamente em Dispatchers.IO.
    }

    @JvmStatic // CORRIGIDO BUG2: mantém uma API conveniente para fluxos que salvaram a key.
    fun revalidateSavedKey(context: Context, callback: Callback): Request { // CORRIGIDO BUG2: revalida a key do cache sem duplicar regra.
        val appContext = context.applicationContext // CORRIGIDO BUG2: usa contexto da aplicação.
        val key = appContext.getSharedPreferences(KeyManager.PREFS_NAME, Context.MODE_PRIVATE).getString("key", "").orEmpty().trim() // CORRIGIDO BUG2: obtém a key salva.
        return revalidateKey(appContext, key, callback) // CORRIGIDO BUG2: aplica limpeza completa antes da nova consulta.
    }

    private fun localSessionExpiry(context: Context): Long = context.applicationContext // CORRIGIDO BUG2: mantém compatibilidade com o monitor atual.
        .getSharedPreferences(KeyManager.PREFS_NAME, Context.MODE_PRIVATE) // CORRIGIDO BUG2: lê o cache centralizado.
        .getLong(KeyManager.SESSION_EXPIRES_AT, 0L) // CORRIGIDO BUG2: expiração permanece em epoch UTC.

    @JvmStatic // CORRIGIDO BUG2: mantém a API de limpeza usada pela tela de bloqueio.
    fun clearLocalSession(context: Context) { // CORRIGIDO BUG2: usa a limpeza completa do KeyManager.
        KeyManager.clearValidationCache(context) // CORRIGIDO BUG2: remove também o indicador de expiração desconhecida.
    }

    private fun readResponse(stream: InputStream?): String {
        if (stream == null) return ""
        return stream.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                val result = StringBuilder()
                val buffer = CharArray(4096)
                while (result.length < MAX_RESPONSE_CHARS) {
                    val remaining = MAX_RESPONSE_CHARS - result.length
                    val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    result.append(buffer, 0, read)
                }
                result.toString()
            }
        }
    }
}
