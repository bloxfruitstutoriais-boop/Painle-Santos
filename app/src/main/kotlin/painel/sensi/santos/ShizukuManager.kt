package painel.sensi.santos // CORRIGIDO BUG1: concentra toda execução remota em Dispatchers.IO.

import android.os.Handler // CORRIGIDO BUG1: devolve callbacks à Main Looper.
import android.os.Looper // CORRIGIDO BUG1: evita tocar Views em thread de shell.
import android.util.Log // CORRIGIDO BUG1: registra exceções de execução/callback.
import kotlinx.coroutines.CoroutineScope // CORRIGIDO BUG1: mantém escopo controlado do executor.
import kotlinx.coroutines.Dispatchers // CORRIGIDO BUG1: usa explicitamente IO para shell.
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob // CORRIGIDO BUG1: uma falha não cancela as demais operações.
import kotlinx.coroutines.launch // CORRIGIDO BUG1: inicia comandos sem bloquear a UI.

/** Fachada única para as operações do Shizuku. */ // CORRIGIDO BUG1: documenta a fronteira segura do executor.
@OptIn(ExperimentalCoroutinesApi::class)
object ShizukuManager { // CORRIGIDO BUG1: impede executores concorrentes espalhados pelo painel.
    interface Callback { // CORRIGIDO BUG1: callback legado mantido para compatibilidade.
        fun onFinished(result: ShizukuBridge.CommandResult) // CORRIGIDO BUG1: resultado chega depois do worker.
    } // CORRIGIDO BUG1: fecha a interface.

    private const val TAG = "ShizukuManager" // CORRIGIDO BUG1: tag dos erros do executor.
    // CORRIGIDO: uma única fila evita vários processos/binder requests simultâneos
    // quando o usuário ativa vários controles em sequência.
    private val remoteDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + remoteDispatcher) // CORRIGIDO BUG1: todos os trabalhos remotos usam uma fila única.
    private val main = Handler(Looper.getMainLooper()) // CORRIGIDO BUG1: callbacks de UI retornam à Main Looper.

    @JvmStatic // CORRIGIDO BUG1: mantém a API Java usada pelo painel.
    fun checkPermission(): Boolean = ShizukuBridge.hasPermission() // CORRIGIDO BUG1: consulta snapshot sem IPC síncrono na Main.

    /** Uso síncrono somente quando o chamador já está em Dispatchers.IO. */ // CORRIGIDO BUG1: deixa o contrato de thread explícito.
    @JvmStatic // CORRIGIDO BUG1: mantém compatibilidade com ShellManager.
    fun execute(command: String): ShizukuBridge.CommandResult { // CORRIGIDO BUG1: captura falha inesperada antes de retornar ao painel.
        return try { // CORRIGIDO BUG1: nenhuma exceção escapa para a UI.
            // CORRIGIDO ALONGAR TELA: pingBinder + permissão são revalidados
            // antes de cada comando, inclusive am compat do helper.
            if (!ShizukuBridge.isAvailable()) {
                ShizukuBridge.CommandResult(false, -1, "", ShizukuBridge.status())
            } else if (!ShizukuBridge.hasPermission()) {
                ShizukuBridge.CommandResult(false, -1, "", ShizukuBridge.status())
            } else {
                ShizukuBridge.executeCommand(command) // CORRIGIDO BUG1: ponte rejeita chamada na Main Thread.
            }
        } catch (error: Throwable) { // CORRIGIDO BUG1: trata binder/processo morto sem crash.
            Log.e(TAG, "erro: ${error.javaClass.simpleName}: ${error.message ?: "falha desconhecida"}", error) // CORRIGIDO BUG1: registra a causa real.
            ShizukuBridge.CommandResult(false, -1, "", error.message ?: "Falha ao executar comando Shizuku") // CORRIGIDO BUG1: devolve erro amigável ao consumidor.
        } // CORRIGIDO BUG1: fecha o try/catch.
    } // CORRIGIDO BUG1: fecha a execução protegida.

    /** Executa um bloco remoto em IO e nunca deixa uma exceção escapar ao loop principal. */ // CORRIGIDO BUG1: usado pelos métodos Java assíncronos da ponte.
    @JvmStatic // CORRIGIDO BUG1: disponibiliza o worker para Java.
    fun launchIo(name: String, work: Runnable) { // CORRIGIDO BUG1: substitui new Thread por Coroutine Dispatchers.IO.
        scope.launch { // CORRIGIDO: fila única; não iniciar workers Shizuku concorrentes.
            try { // CORRIGIDO BUG1: envolve toda a operação remota.
                Log.d("SHIZUKU_DEBUG", "antes de executar: $name") // CORRIGIDO BUG1: log obrigatório antes do comando.
                work.run() // CORRIGIDO BUG1: executa o processo remoto no worker.
                Log.d("SHIZUKU_DEBUG", "depois de executar: $name") // CORRIGIDO BUG1: log obrigatório após a operação.
            } catch (error: Throwable) { // CORRIGIDO BUG1: binder morto/erro de processo vira resultado do chamador.
                Log.e("SHIZUKU_DEBUG", "erro: $error", error) // CORRIGIDO BUG1: log obrigatório com a exceção.
                Log.e(TAG, "Falha no worker Shizuku: $name", error) // CORRIGIDO BUG1: mantém diagnóstico nominal do worker.
            } // CORRIGIDO BUG1: fecha o tratamento do worker.
        } // CORRIGIDO BUG1: fecha a coroutine IO.
    } // CORRIGIDO BUG1: fecha o lançador seguro.

    /** Uso recomendado pela UI: executa sempre em Dispatchers.IO. */ // CORRIGIDO BUG1: evita bloqueio/ANR no painel.
    @JvmStatic // CORRIGIDO BUG1: mantém a chamada Java.
    fun applyOptimization(command: String, callback: Callback?) { // CORRIGIDO BUG1: encapsula comando e retorno em coroutine.
        scope.launch { // CORRIGIDO: execução de comandos permanece na fila única.
            val result = try { // CORRIGIDO BUG1: captura falhas de execução.
                Log.d("SHIZUKU_DEBUG", "antes de executar: $command") // CORRIGIDO BUG1: log obrigatório antes do comando.
                val value = if (!ShizukuBridge.isAvailable() || !ShizukuBridge.hasPermission()) {
                    ShizukuBridge.CommandResult(false, -1, "", ShizukuBridge.status())
                } else {
                    ShizukuBridge.executeCommand(command) // CORRIGIDO BUG1: executa no worker IO.
                }
                Log.d("SHIZUKU_DEBUG", "depois de executar: $command") // CORRIGIDO BUG1: log obrigatório depois do comando.
                value // CORRIGIDO BUG1: entrega o resultado real.
            } catch (error: Throwable) { // CORRIGIDO BUG1: não deixa exceção matar o painel.
                Log.e("SHIZUKU_DEBUG", "erro: $error", error) // CORRIGIDO BUG1: registra a exceção obrigatória.
                ShizukuBridge.CommandResult(false, -1, "", error.message ?: "Falha ao executar comando Shizuku") // CORRIGIDO BUG1: converte falha em resultado amigável.
            } // CORRIGIDO BUG1: fecha proteção da execução.
            if (callback != null) { // CORRIGIDO BUG1: evita callback nulo.
                main.post { // CORRIGIDO BUG1: qualquer View só é tocada na Main Looper.
                    try { callback.onFinished(result) } // CORRIGIDO BUG1: protege callback tardio.
                    catch (error: Throwable) { Log.e(TAG, "Callback Shizuku falhou", error) } // CORRIGIDO BUG1: callback não derruba o processo.
                } // CORRIGIDO BUG1: fecha retorno principal.
            } // CORRIGIDO BUG1: fecha guarda de callback.
        } // CORRIGIDO BUG1: fecha coroutine.
    } // CORRIGIDO BUG1: fecha método assíncrono.
}
