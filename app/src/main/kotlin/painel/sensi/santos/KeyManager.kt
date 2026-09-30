package painel.sensi.santos // CORRIGIDO BUG2: centraliza cache, cálculo UTC e decisão de acesso por dias.

import android.content.Context // CORRIGIDO BUG2: usa o contexto da aplicação para o cache.
import android.util.Log // CORRIGIDO BUG2: registra as datas e a decisão final.
import java.nio.charset.StandardCharsets // CORRIGIDO BUG2: torna o fingerprint determinístico.
import java.security.MessageDigest // CORRIGIDO BUG2: mantém a key fora do cache em texto puro.
import java.time.Instant // CORRIGIDO BUG2: representa os instantes sempre em UTC.
import java.time.ZoneOffset // CORRIGIDO BUG2: formata o diagnóstico em UTC.
import java.time.format.DateTimeFormatter // CORRIGIDO BUG2: formata o diagnóstico sem timezone local.

/** Cache e regra única de liberação da licença. */ // CORRIGIDO BUG2: impede comparações duplicadas/invertidas em Activities e Services.
object KeyManager { // CORRIGIDO BUG2: fornece uma fonte única de verdade para a key.
    const val PREFS_NAME = "santos_session" // CORRIGIDO BUG2: mantém o armazenamento original do app.
    const val SESSION_VALID = "license_session_valid" // CORRIGIDO BUG2: chave do estado validado.
    const val SESSION_KEY_HASH = "license_key_hash" // CORRIGIDO BUG2: identifica a key atualmente validada.
    const val SESSION_EXPIRES_AT = "license_expires_at" // CORRIGIDO BUG2: guarda a expiração em epoch UTC.
    const val SESSION_EXPIRY_KNOWN = "license_expiry_known" // CORRIGIDO BUG2: não confunde expiração ausente com epoch zero.
    const val SESSION_LAST_VALIDATED_AT = "license_last_validated_at" // CORRIGIDO BUG2: registra a validação mais recente.
    const val MILLIS_PER_DAY = 1000L * 60L * 60L * 24L // CORRIGIDO BUG2: divisor explícito exigido para dias UTC.
    private const val TAG = "KeyManager" // CORRIGIDO BUG2: identifica os logs da regra de dias.
    private const val DEBUG_TAG = "KEY_DEBUG" // CORRIGIDO BUG2: facilita a inspeção no Logcat.
    private val UTC_FORMATTER = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC) // CORRIGIDO BUG2: evita timezone local no diagnóstico.

    @JvmStatic // CORRIGIDO BUG2: permite que o OverlayService Java leia o snapshot.
    fun calculateDaysRemaining(expiresAtMillis: Long?, currentAtMillis: Long = System.currentTimeMillis()): Long? { // CORRIGIDO BUG2: calcula dias somente a partir da data de expiração.
        if (expiresAtMillis == null || expiresAtMillis <= 0L) return null // CORRIGIDO BUG2: data nula/inválida vira trial, não key expirada.
        val differenceMillis = expiresAtMillis - currentAtMillis // CORRIGIDO BUG2: aplica dataExpiracao - dataAtual em epoch UTC.
        val days = if (differenceMillis <= 0L) 0L else differenceMillis / MILLIS_PER_DAY // CORRIGIDO BUG2: evita zero incorreto para uma expiração já passada.
        return days // CORRIGIDO BUG2: retorna o valor usado pela comparação >= 7/< 7/<= 0.
    }

    @JvmStatic // CORRIGIDO BUG2: permite testes determinísticos e integração com Java.
    fun accessFromExpiration(valid: Boolean, expiresAtMillis: Long?, currentAtMillis: Long = System.currentTimeMillis()): LicenseKeyInfo { // CORRIGIDO BUG2: concentra toda a decisão de acesso.
        val safeExpiry = expiresAtMillis?.takeIf { it > 0L } // CORRIGIDO BUG2: descarta datas nulas/zero sem transformar em expiração.
        val days = calculateDaysRemaining(safeExpiry, currentAtMillis) // CORRIGIDO BUG2: calcula antes de aplicar qualquer bloqueio.
        val expiryKnown = safeExpiry != null // CORRIGIDO BUG2: marca se a API informou data utilizável.
        val expired = expiryKnown && (safeExpiry!! <= currentAtMillis || (days != null && days <= 0L)) // CORRIGIDO BUG2: <= 0 bloqueia tudo.
        val fullAccess = valid && !expired && days != null && days >= 7L // CORRIGIDO BUG2: >= 7 libera todas as funções licenciadas.
        val trial = valid && !expiryKnown // CORRIGIDO BUG2: data inválida/nula usa tratamento trial seguro.
        val result = when { // CORRIGIDO BUG2: torna a ordem de decisão explícita.
            !valid -> "key não validada" // CORRIGIDO BUG2: sessão inválida não libera recursos.
            expired -> "key expirada: bloqueio total" // CORRIGIDO BUG2: expiração tem prioridade sobre qualquer cache.
            fullAccess -> "liberação total: diasRestantes >= 7" // CORRIGIDO BUG2: condição correta solicitada.
            trial -> "trial: expiração ausente; funções restritas bloqueadas" // CORRIGIDO BUG2: data ausente não é tratada como 0 válido.
            else -> "funções restritas bloqueadas: diasRestantes < 7" // CORRIGIDO BUG2: keys abaixo de sete dias ficam limitadas.
        } // CORRIGIDO BUG2: finaliza a decisão sem inversão premium/trial.
        val currentText = UTC_FORMATTER.format(Instant.ofEpochMilli(currentAtMillis)) // CORRIGIDO BUG2: dataAtual visível em UTC.
        val expiryText = if (safeExpiry == null) "não informada" else UTC_FORMATTER.format(Instant.ofEpochMilli(safeExpiry)) // CORRIGIDO BUG2: dataExpiracao visível em UTC.
        Log.d(DEBUG_TAG, "dataAtual=$currentText dataExpiracao=$expiryText diasRestantes=${days ?: "desconhecido"} resultadoLiberacao=$result") // CORRIGIDO BUG2: registra todos os elementos da decisão.
        return LicenseKeyInfo(valid, expiryKnown, expired, trial, fullAccess, days, safeExpiry ?: 0L, currentAtMillis, currentText, expiryText, result) // CORRIGIDO BUG2: devolve um estado imutável para a UI.
    }

    @JvmStatic // CORRIGIDO BUG2: fornece o snapshot usado pelo painel.
    fun getAccessInfo(context: Context, key: String? = null): LicenseKeyInfo { // CORRIGIDO BUG2: permite invalidar cache de key diferente.
        val now = System.currentTimeMillis() // CORRIGIDO BUG2: captura uma única dataAtual para o cálculo.
        val normalizedKey = key?.trim().orEmpty() // CORRIGIDO BUG2: compara a key normalizada.
        if (normalizedKey.isEmpty()) return LicenseKeyInfo.empty(now) // CORRIGIDO BUG2: ausência de key bloqueia funções restritas.
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) // CORRIGIDO BUG2: lê cache somente da aplicação.
        val valid = prefs.getBoolean(SESSION_VALID, false) // CORRIGIDO BUG2: exige sessão marcada como válida.
        val sameKey = prefs.getString(SESSION_KEY_HASH, "") == keyFingerprint(normalizedKey) // CORRIGIDO BUG2: não reaproveita cache de uma key anterior.
        if (!valid || !sameKey) return LicenseKeyInfo.empty(now) // CORRIGIDO BUG2: cache antigo não libera uma key nova.
        val known = prefs.getBoolean(SESSION_EXPIRY_KNOWN, prefs.getLong(SESSION_EXPIRES_AT, 0L) > 0L) // CORRIGIDO BUG2: compatibilidade com cache da versão anterior.
        val expiry = if (known) prefs.getLong(SESSION_EXPIRES_AT, 0L) else null // CORRIGIDO BUG2: data desconhecida permanece null.
        return accessFromExpiration(true, expiry, now) // CORRIGIDO BUG2: recalcula dias toda vez, sem manter número antigo.
    }

    @JvmStatic // CORRIGIDO BUG2: grava uma nova validação sem preservar a expiração da key anterior.
    fun saveValidSession(context: Context, key: String, expiresAtMillis: Long?) { // CORRIGIDO BUG2: atualiza atomically o cache da key validada.
        val normalizedKey = key.trim() // CORRIGIDO BUG2: usa a mesma normalização da leitura.
        if (normalizedKey.isEmpty()) return // CORRIGIDO BUG2: nunca grava sessão para entrada vazia.
        val expiry = expiresAtMillis?.takeIf { it > 0L } ?: 0L // CORRIGIDO BUG2: data inválida limpa o valor antigo.
        val now = System.currentTimeMillis() // CORRIGIDO BUG2: registra a data atual da validação.
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit() // CORRIGIDO BUG2: grava no cache da aplicação.
            .putBoolean(SESSION_VALID, true) // CORRIGIDO BUG2: marca a nova sessão como válida.
            .putString(SESSION_KEY_HASH, keyFingerprint(normalizedKey)) // CORRIGIDO BUG2: substitui o fingerprint anterior.
            .putLong(SESSION_EXPIRES_AT, expiry) // CORRIGIDO BUG2: não preserva expiração antiga quando a API omite a data.
            .putBoolean(SESSION_EXPIRY_KNOWN, expiry > 0L) // CORRIGIDO BUG2: diferencia data conhecida de trial.
            .putLong(SESSION_LAST_VALIDATED_AT, now) // CORRIGIDO BUG2: registra a nova validação.
            .apply() // CORRIGIDO BUG2: persiste todas as alterações de uma vez.
        accessFromExpiration(true, if (expiry > 0L) expiry else null, now) // CORRIGIDO BUG2: emite log da decisão armazenada.
    }

    @JvmStatic // CORRIGIDO BUG2: botão Revalidar key usa esta limpeza antes da consulta.
    fun clearValidationCache(context: Context) { // CORRIGIDO BUG2: remove também os campos de dias/expiração antigos.
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit() // CORRIGIDO BUG2: abre o cache da aplicação.
            .remove(SESSION_VALID) // CORRIGIDO BUG2: força a nova validação.
            .remove(SESSION_KEY_HASH) // CORRIGIDO BUG2: impede reutilização de key antiga.
            .remove(SESSION_EXPIRES_AT) // CORRIGIDO BUG2: remove expiração velha.
            .remove(SESSION_EXPIRY_KNOWN) // CORRIGIDO BUG2: remove indicador velho.
            .remove(SESSION_LAST_VALIDATED_AT) // CORRIGIDO BUG2: remove timestamp velho.
            .apply() // CORRIGIDO BUG2: conclui a limpeza do cache.
        Log.d(TAG, "cache de validação limpo") // CORRIGIDO BUG2: permite confirmar a ação no Logcat.
    }

    @JvmStatic // CORRIGIDO BUG2: mantém a API pública usada pelo fluxo inicial.
    fun hasValidSession(context: Context, key: String): Boolean { // CORRIGIDO BUG2: valida key, cache e expiração calculada.
        val info = getAccessInfo(context, key) // CORRIGIDO BUG2: usa a mesma regra da UI.
        return info.valid && !info.expired // CORRIGIDO BUG2: uma key <= 0 dias não passa pelo auto-login.
    }

    @JvmStatic // CORRIGIDO BUG2: expõe o fingerprint sem revelar a key.
    fun keyFingerprint(value: String): String { // CORRIGIDO BUG2: centraliza a identificação do cache.
        val digest = MessageDigest.getInstance("SHA-256").digest(value.trim().toByteArray(StandardCharsets.UTF_8)) // CORRIGIDO BUG2: calcula hash estável.
        return digest.joinToString("") { byte -> "%02x".format(byte) } // CORRIGIDO BUG2: mantém formato compatível com a sessão existente.
    }
}
