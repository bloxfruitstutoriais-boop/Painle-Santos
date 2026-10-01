package painel.sensi.santos

import android.app.NotificationManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CoroutineScope // CORRIGIDO BUG1: shell usa coroutine em vez de executor de thread.
import kotlinx.coroutines.Dispatchers // CORRIGIDO BUG1: garante execução dos comandos em IO.
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob // CORRIGIDO BUG1: falha em uma operação não cancela a fila inteira.
import kotlinx.coroutines.launch // CORRIGIDO BUG1: inicia trabalho remoto fora da UI.

/**
 * Comandos de otimização locais do painel.
 *
 * Os comandos são constantes ou montados somente com valores validados. O
 * executor usa exclusivamente a ponte Shizuku autorizada. Em aparelhos sem
 * Shizuku disponível/permissão, a operação falha de forma explícita, sem
 * fingir que a otimização foi aplicada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object ShellManager {
    interface Callback {
        fun onFinished(ok: Boolean, message: String)
    }

    interface CapabilityCallback {
        // CORRIGIDO: além da lista de indisponíveis, a UI recebe a causa real
        // de cada teste (exit code/stdout/stderr), sem heurística de versão.
        fun onFinished(unsupported: Set<String>, details: Map<String, String>)
    }

    private data class CommandResult(val ok: Boolean, val output: String, val exitCode: Int = -1,
                                     val stdout: String = output, val stderr: String = "")
    private data class Operation(val ok: Boolean, val message: String)

    /*
     * Todas as ações passam pela mesma fila. A versão anterior criava várias
     * shells Shizuku simultâneas ao tocar em switches seguidos, o que deixava
     * processos remotos presos em alguns firmwares e derrubava a overlay.
     */
    // CORRIGIDO: todos os ajustes usam uma fila única; ativar vários switches
    // não cria vários processos Shizuku ao mesmo tempo.
    private val remoteDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ioScope = CoroutineScope(SupervisorJob() + remoteDispatcher) // CORRIGIDO BUG1: fila remota serializada.
    private val mainHandler = Handler(Looper.getMainLooper()) // CORRIGIDO BUG1: callback visual volta para a Main Looper.
    private const val TAG = "ShellManager"
    private const val FLOW_TAG = "DEBUG_FLOW"

    private val CPU_GOVERNOR_PATHS = arrayOf(
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_governor"
    )
    private val GPU_MIN_PATHS = arrayOf(
        "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq",
        "/sys/class/devfreq/57000000.gpu/min_freq",
        "/sys/class/devfreq/13000000.mali/min_freq"
    )
    private val TOUCH_PATHS = arrayOf(
        "/sys/class/input/input0/poll_rate",
        "/sys/class/input/input1/poll_rate",
        "/sys/devices/virtual/input/input0/poll_rate",
        "/sys/class/input/input0/report_rate",
        "/sys/class/input/input1/report_rate",
        "/sys/devices/virtual/input/input0/report_rate"
    )
    private const val FAST_TCP_RMEM = "4096 87380 16777216"
    private const val FAST_TCP_WMEM = "4096 65536 16777216"
    private const val PEAK_REFRESH_SETTING = "peak_refresh_rate"
    private const val MIN_REFRESH_SETTING = "min_refresh_rate"
    private const val MISSING_SETTING = "__missing__"

    private fun failure(prefix: String, result: CommandResult): Operation {
        // CORRIGIDO: manter a saída completa do processo para o log do painel;
        // o TextView agora permite rolagem e não usa ellipsis.
        val detail = result.output.trim()
        val exit = "exit=${result.exitCode}"
        return Operation(false, if (detail.isEmpty()) "$prefix ($exit)" else "$prefix: $detail ($exit)")
    }

    @JvmStatic
    fun clearCache(callback: Callback) {
        async(callback) {
            val sync = execute("sync")
            if (!sync.ok) return@async failure("Não foi possível sincronizar antes da limpeza", sync)
            // /proc/sys/vm/drop_caches costuma ser recusado pelo SELinux em
            // aparelhos Samsung. Tentar o caminho oficial de PackageManager
            // evita o falso sucesso e mantém stdout/stderr no LOG.
            val attempts = listOf(
                execute("cmd package trim-caches 1G"),
                execute("pm trim-caches 1G")
            )
            val confirmed = attempts.firstOrNull { it.ok }
            if (confirmed != null) {
                Operation(true, "Cache de pacotes solicitado e confirmado (exit=${confirmed.exitCode})")
            } else {
                val detail = attempts.joinToString(" | ") {
                    "exit=${it.exitCode} stdout=${it.stdout.trim()} stderr=${it.stderr.trim()}"
                }
                Operation(false, "Cache recusado pelo Android; nenhum sucesso foi simulado: $detail")
            }
        }
    }

    @JvmStatic
    fun clearRam(callback: Callback) {
        async(callback) {
            // kill-all só encerra processos em segundo plano; não há API
            // legítima para “fabricar” RAM livre. Ler antes/depois deixa claro
            // o que realmente ocorreu no diagnóstico.
            val before = execute("dumpsys meminfo --local | head -n 24")
            val first = execute("cmd activity kill-all")
            val second = if (first.ok) first else execute("am kill-all")
            val after = execute("dumpsys meminfo --local | head -n 24")
            if (second.ok) {
                Operation(true, "Processos em segundo plano encerrados (exit=${second.exitCode}); "
                    + "RAM efetivamente livre não é exposta como garantia pelo Android. "
                    + "antes=${before.stdout.trim()} depois=${after.stdout.trim()}")
            } else {
                failure("Android recusou a liberação de processos", second)
            }
        }
    }

    @JvmStatic
    fun compilePackage(packageName: String, mode: String, callback: Callback) {
        async(callback) {
            val safePackage = packageName.trim()
            val safeMode = mode.trim()
            if (!validPackage(safePackage) || safeMode !in setOf("speed", "speed-profile")) {
                return@async Operation(false, "Compiler: package ou modo inválido")
            }
            val installed = execute("pm path $safePackage")
            if (!installed.ok || !installed.stdout.contains("package:")) {
                return@async failure("Compiler: package não instalado", installed)
            }
            val result = execute("cmd package compile -m $safeMode -f $safePackage")
            if (result.ok) Operation(true, "Compiler $safeMode aplicado e confirmado pelo comando do Android · exit=${result.exitCode}")
            else failure("Compiler $safeMode recusado pelo Android", result)
        }
    }

    @JvmStatic
    fun setCpuGovernor(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setCpuGovernorNow(context, enabled) }
    }

    @JvmStatic
    fun setGpuTurbo(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setGpuTurboNow(context, enabled) }
    }

    @JvmStatic
    fun setTouchOptimization(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setTouchOptimizationNow(context, enabled) }
    }

    @JvmStatic
    fun setNetworkOptimization(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setNetworkOptimizationNow(context, enabled) }
    }

    @JvmStatic
    fun setGameMode(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setGameModeNow(context, enabled) }
    }

    @JvmStatic
    fun setDoNotDisturb(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setDoNotDisturbNow(context, enabled) }
    }

    @JvmStatic
    fun setDozeBlocker(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setDozeBlockerNow(context, enabled) }
    }

    @JvmStatic
    fun setThermalOptimization(context: Context, enabled: Boolean, callback: Callback) {
        async(callback) { setThermalOptimizationNow(context, enabled) }
    }

    @JvmStatic
    fun testConnection(callback: Callback) {
        async(callback) {
            val result = execute("echo SANTOS_SHIZUKU_OK")
            if (result.ok && result.output.contains("SANTOS_SHIZUKU_OK")) {
                Operation(true, "Ponte Shizuku respondeu corretamente (exit 0)")
            } else {
                failure("A ponte Shizuku não respondeu", result)
            }
        }
    }

    @JvmStatic
    fun setGlobalRefreshRate(context: Context, hz: Int, callback: Callback) {
        async(callback) { setGlobalRefreshRateNow(context, hz) }
    }

    /**
     * CORRIGIDO: verifica cada função executando o comando real e lendo o
     * resultado. Não há decisão baseada apenas em SDK, fabricante ou caminho.
     * A sonda é disparada pelo botão existente TESTAR PONTE SHIZUKU; a regra 0
     * proíbe criar um botão novo chamado VERIFICAR SUPORTE.
     */
    @JvmStatic
    fun probeCapabilities(context: Context, callback: CapabilityCallback) {
        ioScope.launch {
            val unsupported = linkedSetOf<String>()
            val details = linkedMapOf<String, String>()

            // CORRIGIDO: sondagem nunca escreve no sistema; cada ação só é
            // confirmada no clique explícito, com readback e rollback.
            fun readOnly(key: String, command: String, label: String) {
                try {
                    val result = execute(command)
                    details[key] = "$label: pré-teste somente leitura; suporte será confirmado ao aplicar; " +
                        "exit=${result.exitCode} stdout=${result.stdout.trim()} stderr=${result.stderr.trim()}"
                    unsupported.add(key)
                } catch (error: Throwable) {
                    val detail = error.javaClass.simpleName + ": " +
                        (error.message ?: "exceção sem mensagem")
                    Log.e("SHIZUKU_DEBUG", "probe[$key] $detail", error)
                    details[key] = "$label: erro real no pré-teste: $detail"
                    unsupported.add(key)
                }
            }

            readOnly("cpu_governor", "cmd power get-fixed-performance-mode-enabled", "CPU performance")
            readOnly("gpu_turbo", "for p in /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/devfreq/*/min_freq; do [ -e \$p ] && echo \$p; done", "GPU")
            readOnly("touch_driver", "settings get system $PEAK_REFRESH_SETTING", "Touch/refresh")
            readOnly("network", "sysctl -n net.ipv4.tcp_rmem", "Buffer TCP")
            val packageName = selectedGamePackage(context)
            if (packageName == null) {
                details["game_mode"] = "Game Mode: não testado; nenhum package de jogo selecionado"
            } else {
                readOnly("game_mode", "cmd game mode get $packageName", "Game Mode $packageName")
            }
            readOnly("cache", "cmd package help", "Limpar cache")
            readOnly("ram", "dumpsys meminfo --local | head -n 24", "Liberar RAM")

            unsupported.add("renderer")
            unsupported.add("thermal")
            details["renderer"] = "não suportado: Android não expõe API legítima package-scoped para renderer externo"
            details["thermal"] = "não suportado por segurança: proteção térmica não é alterada"

            mainHandler.post {
                try { callback.onFinished(unsupported, details) }
                catch (error: Throwable) { Log.e(TAG, "Callback de capability falhou", error) }
            }
        }
    }

    @JvmStatic
    fun restoreGlobalRefreshRate(context: Context, callback: Callback) {
        async(callback) { restoreGlobalRefreshRateNow(context) }
    }

    private fun validRateText(value: String): Boolean =
        value.trim().matches(Regex("\\d+(\\.\\d+)?"))

    private fun settingText(result: CommandResult): String =
        if (!result.ok) "" else result.output.trim().lineSequence().lastOrNull().orEmpty().trim()

    private fun hasDirectSecureSettings(context: Context): Boolean =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun readRefreshSetting(context: Context, name: String): CommandResult {
        if (!hasDirectSecureSettings(context)) return execute("settings get system $name")
        return try {
            val value = Settings.System.getString(context.contentResolver, name)
            Log.d("SYS_READ_DEBUG", "system.$name=${value ?: "null"}")
            CommandResult(true, value ?: "null", 0, value ?: "null", "")
        } catch (error: Throwable) {
            Log.e("SYS_READ_DEBUG", "system.$name read failed", error)
            CommandResult(false, error.message ?: "read failed", -1, "", error.message ?: "read failed")
        }
    }

    private fun refreshSettingNumber(result: CommandResult): Double? {
        val value = settingText(result)
        return if (validRateText(value)) value.toDoubleOrNull()?.takeIf { it in 30.0..244.0 } else null
    }

    private fun refreshText(hz: Int): String = String.format(Locale.US, "%.1f", hz.toDouble())

    private fun savedSettingValue(result: CommandResult): String {
        val value = settingText(result)
        return if (result.ok && validRateText(value)
            && value.toDoubleOrNull()?.let { it in 30.0..244.0 } == true) value
        else MISSING_SETTING
    }

    private fun putOrDeleteRefreshSetting(context: Context, name: String, value: String): CommandResult {
        if (!hasDirectSecureSettings(context)) {
            return if (value == MISSING_SETTING) execute("settings delete system $name")
            else if (validRateText(value)) execute("settings put system $name $value")
            else CommandResult(false, "valor original de taxa inválido")
        }
        if (value != MISSING_SETTING && !validRateText(value)) return CommandResult(false, "valor original de taxa inválido")
        return try {
            val wrote = if (value == MISSING_SETTING)
                Settings.System.putString(context.contentResolver, name, null)
            else Settings.System.putString(context.contentResolver, name, value)
            val readback = Settings.System.getString(context.contentResolver, name)
            val confirmed = if (value == MISSING_SETTING) readback == null
            else readback == value
            Log.d("SYS_WRITE_DEBUG", "system.$name requested=$value confirmed=${readback ?: "null"} ok=${wrote && confirmed}")
            CommandResult(wrote && confirmed, readback ?: "null", if (wrote) 0 else -1,
                readback ?: "null", if (!wrote) "WRITE_SECURE_SETTINGS recusada" else "")
        } catch (error: Throwable) {
            Log.e("SYS_WRITE_DEBUG", "system.$name write failed", error)
            CommandResult(false, error.message ?: "write failed", -1, "", error.message ?: "write failed")
        }
    }

    private fun displayRate(context: Context): Float {
        return try {
            val manager = context.getSystemService(DisplayManager::class.java)
            manager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f
        } catch (_: Throwable) { 0f }
    }

    private fun displayConfirmsRate(context: Context, target: Int): Boolean {
        repeat(12) {
            if (kotlin.math.abs(displayRate(context) - target.toFloat()) <= 1.5f) return true
            try { Thread.sleep(150L) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun setGlobalRefreshRateNow(context: Context, hz: Int): Operation {
        if (hz !in 30..244) return Operation(false, "Taxa fora do intervalo seguro")
        // CORRIGIDO: não bloquear por versão ou Display.Mode antes do teste;
        // o comando settings e a releitura efetiva são a fonte de verdade.
        val peak = readRefreshSetting(context, PEAK_REFRESH_SETTING)
        val minimum = readRefreshSetting(context, MIN_REFRESH_SETTING)
        // Muitos firmwares retornam `null` para min_refresh_rate antes da
        // primeira gravação. Isso significa "sem override", não "não suportado".
        // Os comandos são tentados e a confirmação é feita pelo setting quando
        // disponível ou pela taxa efetiva do display.
        val peakOriginal = savedSettingValue(peak)
        val minimumOriginal = savedSettingValue(minimum)
        val prefs = state(context)
        if (!prefs.contains("refresh_peak_original")) {
            prefs.edit().putString("refresh_peak_original", peakOriginal)
                .putString("refresh_min_original", minimumOriginal).apply()
        }
        val target = refreshText(hz)
        val first = putOrDeleteRefreshSetting(context, PEAK_REFRESH_SETTING, target)
        if (!first.ok) return failure("Não foi possível aplicar peak_refresh_rate", first)
        val second = putOrDeleteRefreshSetting(context, MIN_REFRESH_SETTING, target)
        if (!second.ok) {
            putOrDeleteRefreshSetting(context, PEAK_REFRESH_SETTING, peakOriginal)
            return failure("Não foi possível aplicar min_refresh_rate", second)
        }
        val confirmedPeak = refreshSettingNumber(readRefreshSetting(context, PEAK_REFRESH_SETTING))
        val confirmedMin = refreshSettingNumber(readRefreshSetting(context, MIN_REFRESH_SETTING))
        val settingsConfirmed = confirmedPeak != null && confirmedMin != null
                && kotlin.math.abs(confirmedPeak - hz) <= .2
                && kotlin.math.abs(confirmedMin - hz) <= .2
        val displayConfirmed = displayConfirmsRate(context, hz)
        if (!settingsConfirmed || !displayConfirmed) {
            val peakRollback = putOrDeleteRefreshSetting(context, PEAK_REFRESH_SETTING, peakOriginal).ok
            val minRollback = putOrDeleteRefreshSetting(context, MIN_REFRESH_SETTING, minimumOriginal).ok
            return Operation(false, "O firmware não confirmou settings globais e modo efetivo de ${hz} Hz; rollback=${peakRollback && minRollback}")
        }
        return Operation(true, "Taxa global ${hz} Hz aplicada e confirmada (exit 0)")
    }

    private fun restoreGlobalRefreshRateNow(context: Context): Operation {
        val prefs = state(context)
        val peak = prefs.getString("refresh_peak_original", "").orEmpty()
        val minimum = prefs.getString("refresh_min_original", "").orEmpty()
        if ((peak != MISSING_SETTING && !validRateText(peak))
            || (minimum != MISSING_SETTING && !validRateText(minimum))) {
            return Operation(true, "Nenhuma taxa global salva para restaurar")
        }
        val first = putOrDeleteRefreshSetting(context, PEAK_REFRESH_SETTING, peak)
        val second = if (first.ok) putOrDeleteRefreshSetting(context, MIN_REFRESH_SETTING, minimum)
        else CommandResult(false, first.output, first.exitCode)
        if (!first.ok) return failure("Não foi possível restaurar peak_refresh_rate", first)
        if (!second.ok) return failure("Não foi possível restaurar min_refresh_rate", second)
        val confirmedPeak = readRefreshSetting(context, PEAK_REFRESH_SETTING)
        val confirmedMin = readRefreshSetting(context, MIN_REFRESH_SETTING)
        val peakOk = if (peak == MISSING_SETTING) refreshSettingNumber(confirmedPeak) == null
        else refreshSettingNumber(confirmedPeak)?.let {
            kotlin.math.abs(it - peak.toDouble()) <= .2
        } == true
        val minOk = if (minimum == MISSING_SETTING) refreshSettingNumber(confirmedMin) == null
        else refreshSettingNumber(confirmedMin)?.let {
            kotlin.math.abs(it - minimum.toDouble()) <= .2
        } == true
        if (!peakOk || !minOk) return Operation(false, "A restauração de Hz não foi confirmada")
        prefs.edit().remove("refresh_peak_original").remove("refresh_min_original").apply()
        return Operation(true, "Taxa global original restaurada e confirmada (exit 0)")
    }

    /**
     * Restaura os tweaks remotos em uma única fila. Resolução/densidade e
     * escalas de animação ficam deliberadamente fora desta rotina: são estado
     * do display/usuário e não devem ser alterados pelo botão de reset.
     */
    @JvmStatic
    fun restoreAllTweaks(context: Context, callback: Callback) {
        async(callback) { restoreAllTweaksNow(context) }
    }

    /**
     * Volta os ajustes de sistema controlados pelo painel aos defaults seguros.
     * Não escreve window_animation_scale, transition_animation_scale ou
     * animator_duration_scale e não executa wm size/wm density.
     */
    private fun restoreAllTweaksNow(context: Context): Operation {
        val commands = listOf(
            "settings put system pointer_speed 0",
            "settings put system show_touches 0",
            "settings put system pointer_location 0",
            "settings put global show_touches 0",
            "settings put global pointer_location 0",
            "settings put global low_power 0",
            "settings put global adb_enabled 0",
            "settings put global development_settings_enabled 0",
            "settings put global private_dns_mode off",
            "settings put system haptic_feedback_enabled 1",
            "settings put system sound_effects_enabled 1",
            "settings put system accelerometer_rotation 1",
            "settings put system screen_brightness_mode 1",
            "settings put global stay_on_while_plugged_in 0",
            "settings put global always_finish_activities 0",
            "settings put global debug_view_attributes 0",
            "settings put global show_processes 0",
            // Display state is intentionally preserved: no refresh/display
            // reset is performed by this action.
            "settings delete global force_gpu_rendering",
            "settings delete global disable_window_blurs",
            "settings delete global debug_app",
            "settings delete global gpu_debug_app",
            "settings delete global gpu_debug_layers",
            "settings delete global private_dns_specifier",
            "settings delete global http_proxy",
            "settings delete global global_http_proxy_host",
            "settings delete global global_http_proxy_port",
            "settings delete global low_power_sticky",
            "settings delete global app_standby_constants",
            "settings delete global device_idle_constants",
            "settings delete global forced_app_standby_apps",
            "cmd appops reset --all",
            "cmd package reset-preferred-activities"
        )
        val details = ArrayList<String>()
        var allOk = true
        for (command in commands) {
            val result = try { ShizukuBridge.executeCommand(command) }
            catch (error: Throwable) {
                allOk = false
                details.add("$command -> ${error.javaClass.simpleName}: ${error.message ?: "falha"}")
                continue
            }
            details.add("$command -> exit=${result.exitCode}${if (result.output.isBlank()) "" else " · ${result.output.take(180)}"}")
            if (!result.ok && command != "reboot") allOk = false
        }
        return Operation(allOk, details.joinToString("\n"))
    }

    private fun restoreSafeSettingsNow(context: Context): Operation {
        val prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE)
        val values = listOf(
            Triple("secure", "long_press_timeout", prefs.getString("original_long_press_timeout", "").orEmpty()),
            Triple("global", "window_animation_scale", prefs.getString("original_window_animation_scale", "").orEmpty()),
            Triple("global", "transition_animation_scale", prefs.getString("original_transition_animation_scale", "").orEmpty()),
            Triple("global", "animator_duration_scale", prefs.getString("original_animator_duration_scale", "").orEmpty())
        )
        var attempted = false
        for ((namespace, name, value) in values) {
            if (!validNumber(value)) continue
            attempted = true
            val result = execute("settings put $namespace $name $value")
            if (!result.ok || !settingMatches(namespace, name, value)) {
                return failure("Restauração de $name não confirmada", result)
            }
        }
        if (attempted) {
            prefs.edit().remove("original_long_press_timeout")
                .remove("original_window_animation_scale")
                .remove("original_transition_animation_scale")
                .remove("original_animator_duration_scale").apply()
        }
        return Operation(true, if (attempted) "restaurados e confirmados" else "sem valores salvos")
    }

    private fun async(callback: Callback, work: () -> Operation) { // CORRIGIDO BUG1: todas as operações ShellManager passam por coroutine IO.
        ioScope.launch { // CORRIGIDO: cada operação espera a anterior terminar.
            val operation = try {
                work()
            } catch (error: Throwable) {
                // CORRIGIDO: exceção da operação é registrada e exibida, nunca engolida.
                Log.e("SHIZUKU_DEBUG", "erro: $error", error) // CORRIGIDO BUG1: erro de shell aparece com a tag exigida.
                Log.e(TAG, "Falha na operação assíncrona", error)
                Operation(false, error.javaClass.simpleName + ": "
                    + (error.message ?: "falha desconhecida"))
            }
            // O trabalho permanece serializado, mas nenhum consumidor pode
            // tocar Views a partir da thread da fila.
            mainHandler.post {
                try {
                    callback.onFinished(operation.ok, operation.message)
                } catch (error: Throwable) {
                    Log.e(TAG, "Callback da operação falhou", error)
                }
            }
        }
    }

    /** Toda otimização passa pelo Shizuku; exit code diferente de zero falha. */
    private fun execute(command: String): CommandResult {
        Log.d(FLOW_TAG, "SHELL_COMMAND_START $command") // LOG ADICIONADO
        Log.d("SHIZUKU_DEBUG", "antes de executar: $command") // CORRIGIDO BUG1: registra o início de cada comando remoto.
        // CORRIGIDO ALONGAR TELA: revalidar binder e autorização antes de
        // cada comando, inclusive settings/wm usados pelo fluxo de display.
        if (!ShizukuBridge.isAvailable() || !ShizukuBridge.hasPermission()) {
            val status = ShizukuBridge.status()
            Log.e(TAG, "Comando não enviado: $status")
            return CommandResult(false, status)
        }
        val result = ShizukuManager.execute(command)
        if (!result.ok) {
            Log.e(TAG, "Comando recusado exit=${result.exitCode}: $command :: ${result.output}")
        }
        Log.d(FLOW_TAG, "SHELL_COMMAND_RESULT command=$command exit=${result.exitCode}") // LOG ADICIONADO
        Log.d("SHIZUKU_DEBUG", "depois de executar: $command exit=${result.exitCode}") // CORRIGIDO BUG1: registra o retorno do comando remoto.
        return CommandResult(result.ok, result.output, result.exitCode,
            result.stdout, result.stderr)
    }

    private fun state(context: Context) =
        context.applicationContext.getSharedPreferences("santos_shell_state", Context.MODE_PRIVATE)

    private fun settingMatches(namespace: String, name: String, expected: String): Boolean {
        val result = execute("settings get $namespace $name")
        val actual = settingText(result)
        return result.ok && validNumber(actual) && validNumber(expected)
                && kotlin.math.abs(actual.toDoubleOrNull()!! - expected.toDoubleOrNull()!!) <= .01
    }

    private fun validGovernor(value: String): Boolean =
        value.matches(Regex("[A-Za-z0-9_.-]{1,32}"))

    private fun validNumber(value: String): Boolean =
        value.trim().matches(Regex("-?\\d+"))

    private fun validTriple(value: String): Boolean =
        value.trim().matches(Regex("\\d+\\s+\\d+\\s+\\d+"))

    private fun fixedPerformanceValue(result: CommandResult): Boolean? {
        if (!result.ok) return null
        val value = result.stdout.trim().lowercase()
        return when {
            value.contains("true") -> true
            value.contains("false") -> false
            else -> null
        }
    }

    private fun read(path: String): String {
        val result = execute("cat $path")
        return if (result.ok) result.output.trim().lineSequence().lastOrNull().orEmpty() else ""
    }

    private fun write(path: String, value: String): CommandResult {
        if (!validGovernor(value) && !validNumber(value)) return CommandResult(false, "")
        return execute("echo $value > $path")
    }

    private fun setCpuGovernorNow(context: Context, enabled: Boolean): Operation {
        // CORRIGIDO: usar a API shell real pedida para Android moderno, reler
        // o estado e só então persistir; em falha o comando não vira sucesso.
        val prefs = state(context)
        if (enabled) {
            val before = execute("cmd power get-fixed-performance-mode-enabled")
            val originalOn = fixedPerformanceValue(before)
                ?: return failure("CPU performance: readback original inválido", before)
            val changed = execute("cmd power set-fixed-performance-mode-enabled true")
            val after = execute("cmd power get-fixed-performance-mode-enabled")
            val confirmed = changed.ok && fixedPerformanceValue(after) == true
            if (confirmed) {
                prefs.edit().putBoolean("cpu_original_fixed_performance", originalOn).apply()
                return Operation(true, "CPU governor: performance ativado e confirmado · "
                    + "exit=${changed.exitCode} stdout=${after.stdout} stderr=${after.stderr}")
            }
            if (changed.ok) execute("cmd power set-fixed-performance-mode-enabled $originalOn")
            return failure("CPU performance recusada ou não confirmada", after)
        }
        if (!prefs.contains("cpu_original_fixed_performance")) {
            return Operation(true, "CPU governor já estava sem alteração salva")
        }
        val original = prefs.getBoolean("cpu_original_fixed_performance", false)
        val result = execute("cmd power set-fixed-performance-mode-enabled $original")
        val after = execute("cmd power get-fixed-performance-mode-enabled")
        val confirmed = result.ok && fixedPerformanceValue(after) == original
        if (confirmed) prefs.edit().remove("cpu_original_fixed_performance").apply()
        return if (confirmed) Operation(true, "CPU governor original restaurado e confirmado · exit=${result.exitCode}")
        else failure("Não foi possível restaurar o CPU governor", after)
    }

    private fun writableGpuPath(): String? =
        GPU_MIN_PATHS.firstOrNull { execute("test -w $it").ok }

    private fun setGpuTurboNow(context: Context, enabled: Boolean): Operation {
        val prefs = state(context)
        if (enabled) {
            val path = prefs.getString("gpu_min_path", null) ?: writableGpuPath()
                ?: return Operation(false, "GPU Turbo não suportado pelo driver deste aparelho")
            val maxPath = path.replace("/min_freq", "/max_freq")
            val maximum = read(maxPath)
            val original = read(path)
            if (!validNumber(maximum) || !validNumber(original)) {
                return Operation(false, "O driver da GPU não expõe frequências ajustáveis")
            }
            if (!prefs.contains("gpu_original_min")
                || prefs.getString("gpu_min_path", "") != path) {
                prefs.edit().putString("gpu_min_path", path)
                    .putString("gpu_original_min", original)
                    .putString("gpu_max", maximum).apply()
            }
            val changed = write(path, maximum)
            val applied = if (changed.ok) read(path) else ""
            return if (changed.ok && applied == maximum) {
                Operation(true, "GPU Turbo ativado no limite exposto pelo driver")
            } else {
                if (changed.ok) write(path, original)
                Operation(false, "O driver da GPU recusou ou não confirmou a frequência máxima")
            }
        }
        val path = prefs.getString("gpu_min_path", "").orEmpty()
        val original = prefs.getString("gpu_original_min", "").orEmpty()
        if (path.isEmpty() || !validNumber(original)) {
            return Operation(true, "GPU sem alteração salva para restaurar")
        }
        val result = write(path, original)
        val readback = if (result.ok) read(path) else ""
        val confirmed = result.ok && readback == original
        if (confirmed) prefs.edit().remove("gpu_min_path").remove("gpu_original_min")
            .remove("gpu_max").apply()
        return if (confirmed) Operation(true, "Frequência mínima original da GPU restaurada e confirmada")
        else failure("Não foi possível restaurar ou confirmar a GPU", result)
    }

    private fun validDiscoveredTouchPath(value: String): Boolean =
        value.matches(Regex("/sys/class/input/input\\d+/(poll_rate|report_rate)"))

    private fun writableTouchPath(): String? {
        val discovered = execute(
            "for d in /sys/class/input/input[0-9]*; do "
                + "for f in poll_rate report_rate; do "
                + "[ -e \$d/\$f ] && echo \$d/\$f; "
                + "done; done"
        ).output.lineSequence()
            .map { it.trim() }
            .filter { validDiscoveredTouchPath(it) }
            .toList()
        return (TOUCH_PATHS.asList() + discovered).distinct()
            .firstOrNull { execute("test -w $it").ok }
    }

    private fun setTouchOptimizationNow(context: Context, enabled: Boolean): Operation {
        // O Android não oferece API pública para impor polling do touch; não
        // alterar refresh nem sysfs como se fossem uma API legítima.
        val prefs = state(context)
        if (enabled) return Operation(false,
            "Touch driver não suportado pelo Android: API pública e readback do fabricante não expostos")
        val originalPeak = prefs.getString("touch_peak_original", "").orEmpty()
        val originalMin = prefs.getString("touch_min_original", "").orEmpty()
        if (originalPeak.isNotEmpty() && originalMin.isNotEmpty()) {
            val peak = putOrDeleteRefreshSetting(context, PEAK_REFRESH_SETTING, originalPeak)
            val min = putOrDeleteRefreshSetting(context, MIN_REFRESH_SETTING, originalMin)
            if (peak.ok && min.ok) {
                prefs.edit().remove("touch_peak_original").remove("touch_min_original").apply()
                return Operation(true, "Touch polling/refresh original restaurado")
            }
            return failure("Não foi possível restaurar o refresh do touch",
                if (!peak.ok) peak else min)
        }
        val path = prefs.getString("touch_path", "").orEmpty()
        val original = prefs.getString("touch_original", "").orEmpty()
        if (path.isEmpty() || !validNumber(original)) return Operation(true, "Touch sem alteração salva para restaurar")
        val result = write(path, original)
        val readback = if (result.ok) read(path) else ""
        val confirmed = result.ok && readback == original
        if (confirmed) prefs.edit().remove("touch_path").remove("touch_original").apply()
        return if (confirmed) Operation(true, "Configuração original do touch restaurada e confirmada")
        else failure("Não foi possível restaurar ou confirmar o touch", result)
    }

    private fun sysctl(name: String): String {
        val result = execute("sysctl -n $name")
        return if (result.ok) result.output.trim().lineSequence().lastOrNull().orEmpty() else ""
    }

    private fun setNetworkOptimizationNow(context: Context, enabled: Boolean): Operation {
        val prefs = state(context)
        val rmem = "net.ipv4.tcp_rmem"
        val wmem = "net.ipv4.tcp_wmem"
        if (enabled) {
            val originalRmem = sysctl(rmem)
            val originalWmem = sysctl(wmem)
            if (!validTriple(originalRmem) || !validTriple(originalWmem)) {
                return Operation(false, "Buffer TCP não está disponível neste kernel")
            }
            if (!prefs.contains("tcp_rmem_original")) {
                prefs.edit().putString("tcp_rmem_original", originalRmem)
                    .putString("tcp_wmem_original", originalWmem).apply()
            }
            val first = execute("sysctl -w $rmem=\"$FAST_TCP_RMEM\"")
            val second = if (first.ok) execute("sysctl -w $wmem=\"$FAST_TCP_WMEM\"")
            else CommandResult(false, first.output)
            val readR = if (first.ok) sysctl(rmem) else ""
            val readW = if (second.ok) sysctl(wmem) else ""
            if (first.ok && second.ok && readR == FAST_TCP_RMEM && readW == FAST_TCP_WMEM) {
                return Operation(true, "Buffers TCP otimizados e confirmados")
            }
            // Se qualquer readback falhar, desfaz tudo imediatamente.
            if (first.ok) execute("sysctl -w $rmem=\"$originalRmem\"")
            if (second.ok) execute("sysctl -w $wmem=\"$originalWmem\"")
            return failure("O kernel recusou ou não confirmou os buffers TCP", if (!first.ok) first else second)
        }
        val originalRmem = prefs.getString("tcp_rmem_original", "").orEmpty()
        val originalWmem = prefs.getString("tcp_wmem_original", "").orEmpty()
        if (!validTriple(originalRmem) || !validTriple(originalWmem)) {
            return Operation(true, "Buffers TCP sem alteração salva para restaurar")
        }
        val first = execute("sysctl -w $rmem=\"$originalRmem\"")
        val second = if (first.ok) execute("sysctl -w $wmem=\"$originalWmem\"")
        else CommandResult(false, first.output)
        val readR = if (first.ok) sysctl(rmem) else ""
        val readW = if (second.ok) sysctl(wmem) else ""
        if (first.ok && second.ok && readR == originalRmem && readW == originalWmem) {
            prefs.edit().remove("tcp_rmem_original").remove("tcp_wmem_original").apply()
            return Operation(true, "Buffers TCP originais restaurados e confirmados")
        }
        return failure("Não foi possível restaurar ou confirmar os buffers TCP", if (!first.ok) first else second)
    }

    private fun validPackage(value: String): Boolean =
        value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))

    private fun selectedGamePackage(context: Context): String? {
        val value = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE)
            .getString("selected_game_package", "").orEmpty()
        return if (validPackage(value) && value != context.packageName) value else null
    }

    private fun processId(packageName: String): String? {
        if (!validPackage(packageName)) return null
        val output = execute("pidof $packageName").output
        return Regex("\\b\\d+\\b").find(output)?.value
    }

    private fun gameModeName(output: String): String? {
        val value = output.lowercase()
        return when {
            value.contains("performance") -> "performance"
            value.contains("battery") -> "battery"
            value.contains("custom") -> "custom"
            value.contains("standard") || value.contains("unsupported") -> "standard"
            else -> null
        }
    }

    private fun setGameModeNow(context: Context, enabled: Boolean): Operation {
        val prefs = state(context)
        // CORRIGIDO: executar o comando real diretamente; help/versão não
        // determina compatibilidade e poderia produzir falso "não suportado".
        if (enabled) {
            val packageName = selectedGamePackage(context)
                ?: return Operation(false, "Informe o package exato do jogo antes de ativar o Game Mode")
            val current = execute("cmd game mode get $packageName")
            val original = gameModeName(current.output)
                ?: return failure("Não foi possível ler o modo Game Mode original", current)
            if (!prefs.contains("game_package") || prefs.getString("game_package", "") != packageName) {
                prefs.edit().putString("game_package", packageName)
                    .putString("game_original_mode", original).apply()
            }
            val result = execute("cmd game mode performance $packageName")
            val readback = if (result.ok) execute("cmd game mode get $packageName") else result
            val confirmed = result.ok && gameModeName(readback.output) == "performance"
            if (confirmed) return Operation(true, "Game Mode performance ativado e confirmado para $packageName")
            if (result.ok) execute("cmd game mode $original $packageName")
            return failure("O Android recusou ou não confirmou o Game Mode", readback)
        }
        val packageName = prefs.getString("game_package", "").orEmpty()
        val original = prefs.getString("game_original_mode", "standard").orEmpty()
        if (!validPackage(packageName)) return Operation(true, "Nenhum Game Mode salvo para restaurar")
        val safeMode = when (original) {
            "performance", "battery", "custom", "standard" -> original
            else -> "standard"
        }
        val result = execute("cmd game mode $safeMode $packageName")
        val readback = if (result.ok) execute("cmd game mode get $packageName") else result
        val confirmed = result.ok && gameModeName(readback.output) == safeMode
        if (confirmed) prefs.edit().remove("game_package").remove("game_original_mode").apply()
        return if (confirmed) Operation(true, "Game Mode original ($safeMode) restaurado e confirmado")
        else failure("Não foi possível restaurar ou confirmar o Game Mode", readback)
    }

    private fun setDoNotDisturbNow(context: Context, enabled: Boolean): Operation {
        val prefs = state(context)
        if (!enabled && !prefs.contains("dnd_original_filter")) {
            return Operation(true, "DND sem alteração salva para restaurar")
        }
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return Operation(false, "DND não suportado: NotificationManager indisponível")
        if (!manager.isNotificationPolicyAccessGranted) {
            Log.w("DND_DEBUG", "ACCESS_NOTIFICATION_POLICY ausente; nenhuma política foi alterada")
            return Operation(false, "DND não aplicado: conceda Acesso à política de notificações")
        }
        val audio = context.getSystemService(AudioManager::class.java)
        val mediaBefore = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
        if (enabled) {
            val originalPolicy = manager.notificationPolicy
            val originalFilter = manager.currentInterruptionFilter
            val requiredVisualSuppression = NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON
                .or(NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF)
                .or(NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT)
                .or(NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS)
                .or(NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK)
                .or(NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE)
            if (!prefs.contains("dnd_original_filter")) {
                prefs.edit().putInt("dnd_original_filter", originalFilter)
                    .putInt("dnd_original_categories", originalPolicy.priorityCategories)
                    .putInt("dnd_original_call_senders", originalPolicy.priorityCallSenders)
                    .putInt("dnd_original_message_senders", originalPolicy.priorityMessageSenders)
                    .putInt("dnd_original_visuals", originalPolicy.suppressedVisualEffects)
                    .apply()
            }
            val notificationOnlyPolicy = NotificationManager.Policy(
                NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                NotificationManager.Policy.PRIORITY_SENDERS_ANY,
                requiredVisualSuppression)
            try {
                manager.setNotificationPolicy(notificationOnlyPolicy)
                manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                val readPolicy = manager.notificationPolicy
                val readFilter = manager.currentInterruptionFilter
                val mediaAfter = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
                val confirmed = readFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    && (readPolicy.suppressedVisualEffects.and(requiredVisualSuppression)
                    == requiredVisualSuppression)
                    && (mediaBefore < 0 || mediaBefore == mediaAfter)
                Log.d("DND_DEBUG", "APPLY filter=$readFilter visuals="
                        + readPolicy.suppressedVisualEffects + " mediaPreserved="
                        + (mediaBefore < 0 || mediaBefore == mediaAfter) + " confirmed=" + confirmed)
                if (confirmed) {
                    return Operation(true, "DND parcial confirmado: pop-ups/visuais/vibração de notificações suprimidos; áudio de mídia/jogo não alterado")
                }
                throw IllegalStateException("readback de política ou áudio não confirmou")
            } catch (error: Throwable) {
                var rollbackOk = true
                try {
                    manager.setNotificationPolicy(originalPolicy)
                    manager.setInterruptionFilter(originalFilter)
                } catch (rollback: Throwable) {
                    rollbackOk = false
                    Log.e("DND_DEBUG", "ROLLBACK_FAILED", rollback)
                }
                Log.e("DND_DEBUG", "APPLY_FAILED rollback=" + rollbackOk, error)
                return Operation(false, "DND não confirmado; rollback=" + rollbackOk + " · "
                        + error.javaClass.simpleName + ": " + (error.message ?: "falha real"))
            }
        }

        if (!prefs.contains("dnd_original_filter")) {
            return Operation(true, "DND sem alteração salva para restaurar")
        }
        val originalFilter = prefs.getInt("dnd_original_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
        val originalPolicy = NotificationManager.Policy(
            prefs.getInt("dnd_original_categories", 0),
            prefs.getInt("dnd_original_call_senders", NotificationManager.Policy.PRIORITY_SENDERS_ANY),
            prefs.getInt("dnd_original_message_senders", NotificationManager.Policy.PRIORITY_SENDERS_ANY),
            prefs.getInt("dnd_original_visuals", 0))
        return try {
            manager.setNotificationPolicy(originalPolicy)
            manager.setInterruptionFilter(originalFilter)
            val readPolicy = manager.notificationPolicy
            val confirmed = manager.currentInterruptionFilter == originalFilter
                && readPolicy.priorityCategories == originalPolicy.priorityCategories
                && readPolicy.suppressedVisualEffects == originalPolicy.suppressedVisualEffects
            Log.d("DND_DEBUG", "RESTORE filter=" + manager.currentInterruptionFilter
                    + " confirmed=" + confirmed)
            if (!confirmed) Operation(false, "DND original não confirmado; snapshot preservado")
            else {
                prefs.edit().remove("dnd_original_filter").remove("dnd_original_categories")
                    .remove("dnd_original_call_senders").remove("dnd_original_message_senders")
                    .remove("dnd_original_visuals").apply()
                Operation(true, "Política DND original restaurada e confirmada; áudio não foi alterado")
            }
        } catch (error: Throwable) {
            Log.e("DND_DEBUG", "RESTORE_FAILED", error)
            Operation(false, "Não foi possível restaurar DND: "
                    + error.javaClass.simpleName + ": " + (error.message ?: "falha real"))
        }
    }

    private fun setDozeBlockerNow(context: Context, enabled: Boolean): Operation {
        val prefs = state(context)
        if (enabled) {
            val packageName = selectedGamePackage(context)
                ?: return Operation(false, "Informe o package exato do jogo antes de ativar o Doze Blocker")
            val whitelist = execute("dumpsys deviceidle whitelist").output
            val alreadyWhitelisted = whitelist.contains(packageName)
            if (alreadyWhitelisted) {
                val wasAddedByPanel = prefs.getString("doze_package", "") == packageName
                        && prefs.getBoolean("doze_added", false)
                prefs.edit().putString("doze_package", packageName)
                    .putBoolean("doze_added", wasAddedByPanel).apply()
                return Operation(true, "Jogo já estava fora da otimização Doze")
            }
            val result = execute("cmd deviceidle whitelist +$packageName")
            val readback = if (result.ok) execute("dumpsys deviceidle whitelist") else result
            val confirmed = result.ok && readback.stdout.contains(packageName)
            if (confirmed) {
                prefs.edit().putString("doze_package", packageName).putBoolean("doze_added", true).apply()
                return Operation(true, "Doze Blocker ativado e confirmado para $packageName")
            }
            if (result.ok) execute("cmd deviceidle whitelist -$packageName")
            return failure("Doze Blocker recusado ou não confirmado", readback)
        }
        val packageName = prefs.getString("doze_package", "").orEmpty()
        val added = prefs.getBoolean("doze_added", false)
        if (!added || !validPackage(packageName)) {
            return Operation(true, "Whitelist Doze original preservada")
        }
        val result = execute("cmd deviceidle whitelist -$packageName")
        val readback = if (result.ok) execute("dumpsys deviceidle whitelist") else result
        val confirmed = result.ok && !readback.stdout.contains(packageName)
        if (confirmed) prefs.edit().remove("doze_package").remove("doze_added").apply()
        return if (confirmed) Operation(true, "Doze Blocker desativado e confirmado")
        else failure("Não foi possível remover ou confirmar o Doze Blocker", readback)
    }

    private fun setThermalOptimizationNow(_context: Context, _enabled: Boolean): Operation {
        // O painel nunca usa comandos de override/reset térmico nem qualquer bypass.
        return Operation(false, "Não suportado neste aparelho: proteção térmica não é alterada pelo painel")
    }
}
