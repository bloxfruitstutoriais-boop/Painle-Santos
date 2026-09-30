package painel.sensi.santos;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * Ponte opcional para o Shizuku.
 *
 * Todos os comandos desta classe são tokens fixos de uma allowlist. Nenhum
 * texto digitado pelo usuário, key ou campo da interface vira comando.
 */
public final class ShizukuBridge {
    public static final int REQUEST_CODE = 4817;

    public interface Callback { void onFinished(boolean ok, String message); }

    // ADICIONADO: todos os callbacks públicos retornam pela Main Looper, mesmo
    // quando a operação foi disparada por uma thread de shell.
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    /*
     * O painel pode disparar uma leitura de display, uma restauração e uma
     * otimização quase ao mesmo tempo. Serializar os processos remotos evita
     * binder/processos órfãos em firmwares que não lidam bem com concorrência.
     */
    private static final Object PROCESS_LOCK = new Object();
    // ADICIONADO: snapshot thread-safe usado pelo cabeçalho de status do overlay.
    private static volatile String lastCommand = "nenhum";
    // ADICIONADO: resultado textual real do último processo remoto.
    private static volatile String lastCommandResult = "aguardando";
    // ADICIONADO: exit code separado para não confundir falha de permissão com
    // erro retornado pelo comando Android.
    private static volatile int lastExitCode = Integer.MIN_VALUE;
    private static volatile String lastStdout = "";
    private static volatile String lastStderr = "";
    // Diagnóstico separado: autorização, criação do processo e exceção não são
    // o mesmo erro e não podem aparecer como um único "falhou".
    private static volatile String lastBridgeState = "não testado";
    private static volatile boolean lastRemoteProcessCreated;
    private static volatile String lastException = "";
    // Snapshot usado pela UI: nenhuma consulta Binder síncrona na Main Thread.
    private static volatile boolean lastAvailableSnapshot;
    private static volatile boolean lastPermissionSnapshot;
    private static volatile String lastStatusSnapshot = "Shizuku não consultado";

    public interface StatusCallback {
        void onFinished(boolean available, boolean permission, String status);
    }

    /** Resultado real de uma execução remota pelo processo shell do Shizuku. */
    public static final class CommandResult {
        public final boolean ok;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final String output;

        // ADICIONADO: construtor mantém stdout, stderr e exit code disponíveis ao chamador.
        CommandResult(boolean ok, int exitCode, String stdout, String stderr) {
            this.ok = ok;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            String combined = this.stdout;
            if (!this.stderr.isEmpty()) {
                if (!combined.isEmpty()) combined += "\n";
                combined += this.stderr;
            }
            this.output = combined.trim();
        }
    }

    public interface DisplayInfoCallback {
        void onFinished(boolean ok, String message, DisplayInfo info);
    }

    public static final class DisplayInfo {
        public final String physicalSize;
        public final String overrideSize;
        public final int physicalDensity;
        public final int overrideDensity;
        // ADICIONADO: valores efetivos separados dos valores físicos/override.
        public final String currentSize;
        // ADICIONADO: densidade efetiva antes de qualquer nova alteração.
        public final int currentDensity;
        // ADICIONADO: rotação lida por dumpsys para orientar largura/altura.
        public final int rotation;
        // ADICIONADO: overscan só é exposto quando o firmware realmente responde.
        public final boolean overscanSupported;
        // ADICIONADO: texto retornado pelo firmware quando overscan é legível.
        public final String overscan;

        DisplayInfo(String physicalSize, String overrideSize,
                    int physicalDensity, int overrideDensity,
                    int rotation, boolean overscanSupported, String overscan) {
            this.physicalSize = physicalSize;
            this.overrideSize = overrideSize;
            this.physicalDensity = physicalDensity;
            this.overrideDensity = overrideDensity;
            this.currentSize = overrideSize.isEmpty() ? physicalSize : overrideSize;
            this.currentDensity = overrideDensity > 0 ? overrideDensity : physicalDensity;
            this.rotation = rotation;
            this.overscanSupported = overscanSupported;
            this.overscan = overscan == null ? "" : overscan;
        }
    }

    private ShizukuBridge() {}

    public static boolean isAvailable() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return lastAvailableSnapshot;
        }
        try {
            boolean available = Shizuku.pingBinder();
            lastAvailableSnapshot = available;
            if (!available) {
                lastPermissionSnapshot = false;
                lastStatusSnapshot = "Shizuku não iniciado (binder indisponível)";
            }
            Log.d(FLOW_TAG, "SHIZUKU_PING available=" + available);
            return available;
        } catch (Throwable error) {
            lastAvailableSnapshot = false;
            lastPermissionSnapshot = false;
            lastStatusSnapshot = "Binder do Shizuku indisponível (" + error.getClass().getSimpleName() + ")";
            Log.e(TAG, "pingBinder falhou", error);
            return false;
        }
    }

    public static boolean hasPermission() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return lastAvailableSnapshot && lastPermissionSnapshot;
        }
        try {
            if (!isAvailable()) return false;
            boolean granted = Build.VERSION.SDK_INT < 23
                    || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            lastPermissionSnapshot = granted;
            lastStatusSnapshot = granted
                    ? "Shizuku iniciado e app autorizado"
                    : "App não autorizado no Shizuku";
            Log.d(FLOW_TAG, "SHIZUKU_PERMISSION granted=" + granted);
            return granted;
        } catch (Throwable error) {
            lastPermissionSnapshot = false;
            lastStatusSnapshot = "App não autorizado no Shizuku ("
                    + error.getClass().getSimpleName() + ")";
            Log.e(TAG, "checkSelfPermission falhou", error);
            return false;
        }
    }

    public static String status() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return lastStatusSnapshot;
        }
        try {
            android.os.IBinder binder = Shizuku.getBinder();
            if (binder == null) {
                lastAvailableSnapshot = false;
                lastPermissionSnapshot = false;
                lastStatusSnapshot = "Shizuku não iniciado (binder indisponível)";
                return lastStatusSnapshot;
            }
            if (!binder.pingBinder()) {
                lastAvailableSnapshot = false;
                lastPermissionSnapshot = false;
                lastStatusSnapshot = "Binder do Shizuku indisponível";
                return lastStatusSnapshot;
            }
            lastAvailableSnapshot = true;
            if (Build.VERSION.SDK_INT >= 23
                    && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                lastPermissionSnapshot = false;
                lastStatusSnapshot = "App não autorizado no Shizuku";
                return lastStatusSnapshot;
            }
            lastPermissionSnapshot = true;
            lastStatusSnapshot = "Shizuku iniciado e app autorizado";
            return lastStatusSnapshot;
        } catch (SecurityException error) {
            lastPermissionSnapshot = false;
            lastStatusSnapshot = "App não autorizado no Shizuku (SecurityException)";
            Log.e(TAG, "status: app não autorizado/binder recusou a consulta", error);
            return lastStatusSnapshot;
        } catch (Throwable error) {
            lastAvailableSnapshot = false;
            lastPermissionSnapshot = false;
            lastStatusSnapshot = "Binder do Shizuku indisponível ("
                    + error.getClass().getSimpleName() + ")";
            Log.e(TAG, "status do Shizuku falhou", error);
            return lastStatusSnapshot;
        }
    }

    /** Sonda Binder/permission em thread própria e devolve o snapshot na UI. */
    public static void refreshStatusAsync(final android.content.Context context,
                                          final StatusCallback callback) {
        ShizukuManager.launchIo("santos-shizuku-status", () -> { // CORRIGIDO BUG1: status/binder roda em Coroutine Dispatchers.IO.
            try {
                if (context != null) {
                    // Em processos não-provider, solicitar explicitamente o binder
                    // evita que o primeiro ping ocorra antes da entrega do binder.
                    ShizukuProvider.requestBinderForNonProviderProcess(
                            context.getApplicationContext());
                }
            } catch (Throwable error) {
                Log.e(TAG, "Solicitação do binder Shizuku falhou", error);
            }
            boolean available = isAvailable();
            boolean permission = available && hasPermission();
            String current = status();
            if (callback != null) {
                MAIN_HANDLER.post(() -> {
                    try {
                        callback.onFinished(available, permission, current);
                    } catch (Throwable error) {
                        Log.e(TAG, "Callback de status Shizuku falhou", error);
                    }
                });
            }
        }); // CORRIGIDO BUG1: encerra a coroutine IO sem criar thread manual.
    }

    /** Compatibilidade para chamadas sem Contexto. */
    public static void refreshStatusAsync(final StatusCallback callback) {
        refreshStatusAsync(null, callback);
    }

    /** Chamado pelo serviço quando o binder morre; invalida o diagnóstico anterior. */
    public static void onBinderDead() {
        lastAvailableSnapshot = false;
        lastPermissionSnapshot = false;
        lastStatusSnapshot = "Binder do Shizuku indisponível";
        lastBridgeState = "binder indisponível";
        lastRemoteProcessCreated = false;
        lastException = "Shizuku.OnBinderDeadListener";
        lastExitCode = Integer.MIN_VALUE;
        lastCommandResult = "ponte invalidada";
        recordStreams("", "Shizuku binder indisponível");
        Log.e(TAG, "Binder do Shizuku morreu; ponte invalidada");
    }

    public static void requestPermission() {
        try {
            Log.d(FLOW_TAG, "SHIZUKU_PERMISSION_REQUEST"); // LOG ADICIONADO
            if (isAvailable() && !hasPermission()) Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable error) {
            Log.e(TAG, "requestPermission falhou", error);
        }
    }

    private static final String TAG = "ShizukuBridge";
    private static final String FLOW_TAG = "DEBUG_FLOW";
    // CORRIGIDO: impedir que uma shell remota travada retenha threads/callbacks
    // indefinidamente e bloqueie futuras ações do painel.
    private static final long COMMAND_TIMEOUT_MS = 15_000L;

    /**
     * Executa uma linha shell somente pelo processo remoto do Shizuku.
     * A versão 13.1.5 não possui Shizuku.executeCommand; o ponto oficial de
     * execução é Shizuku.newProcess(). Este método é a única porta usada pelo app.
     */
    public static CommandResult executeCommand(String command) {
        Log.d("SHIZUKU_DEBUG", "antes de executar: " + command); // CORRIGIDO BUG1: registra entrada da API pública.
        if (command == null || command.trim().isEmpty()) {
            recordFailure("comando vazio", "Comando vazio");
            return new CommandResult(false, -1, "", "Comando vazio");
        }
        // A ponte nunca deve bloquear a UI. Todas as chamadas atuais passam
        // por ShellManager/threads próprias; uma chamada indevida falha claro.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            recordFailure(command, "executeCommand chamado na Main Thread");
            Log.e(TAG, "Execução remota recusada na Main Thread: " + command);
            return new CommandResult(false, -1, "",
                    "Execução remota recusada na Main Thread");
        }
        // sh -c é criado remotamente pelo Shizuku.newProcess; não há shell local.
        try { // CORRIGIDO BUG1: toda chamada pública fica protegida contra binder/processo morto.
            CommandResult result = executeArgv(new String[]{"sh", "-c", command}); // CORRIGIDO BUG1: execução permanece no processo remoto do Shizuku.
            Log.d("SHIZUKU_DEBUG", "depois de executar: " + command + " ok=" + result.ok); // CORRIGIDO BUG1: registra retorno sem tocar UI.
            return result; // CORRIGIDO BUG1: devolve erro estruturado ao consumidor.
        } catch (Throwable error) { // CORRIGIDO BUG1: impede exceção de fechar o painel.
            Log.e("SHIZUKU_DEBUG", "erro: " + error, error); // CORRIGIDO BUG1: registra causa solicitada.
            return new CommandResult(false, -1, "", error.getMessage() == null ? "Falha ao executar comando Shizuku" : error.getMessage()); // CORRIGIDO BUG1: converte falha em mensagem amigável.
        } // CORRIGIDO BUG1: encerra proteção da API pública.
    }

    // API 13.1.5 mantém newProcess privado; esta é a assinatura real da API.
    // O método é resolvido uma vez, sem fallback local, Runtime.exec ou ProcessBuilder.
    private static volatile java.lang.reflect.Method NEW_PROCESS_METHOD;

    private static java.lang.reflect.Method newProcessMethod() throws Exception {
        java.lang.reflect.Method method = NEW_PROCESS_METHOD;
        if (method != null) return method;
        synchronized (ShizukuBridge.class) {
            method = NEW_PROCESS_METHOD;
            if (method == null) {
                method = Shizuku.class.getDeclaredMethod(
                        "newProcess", String[].class, String[].class, String.class);
                method.setAccessible(true);
                NEW_PROCESS_METHOD = method;
                Log.d(FLOW_TAG, "SHIZUKU_NEW_PROCESS_SIGNATURE_READY"); // LOG ADICIONADO
            }
        }
        return method;
    }

    private static ShizukuRemoteProcess newProcess(String[] command) throws Exception {
        try {
            return (ShizukuRemoteProcess) newProcessMethod().invoke(null, command, null, null);
        } catch (java.lang.reflect.InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new Exception(cause);
        }
    }

    private static void copyStream(java.io.InputStream stream, StringBuilder sink) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sink.append(line).append('\n');
        } catch (Throwable error) {
            // A drenagem é feita em thread própria; qualquer falha ainda entra
            // no diagnóstico, em vez de ser convertida em sucesso.
            sink.append(error.getClass().getSimpleName()).append(": ")
                    .append(error.getMessage() == null ? "stream error" : error.getMessage());
            Log.e(TAG, "Falha drenando saída do processo remoto", error);
        }
    }

    private static boolean remoteAlive(ShizukuRemoteProcess process) {
        try {
            return process.alive();
        } catch (Throwable error) {
            // Se o binder morreu, o processo não pode ser consultado com
            // segurança; tratá-lo como vivo evita destruir/consultar exitValue
            // durante a janela de corrida e deixa a exceção no diagnóstico.
            Log.w(TAG, "Não foi possível consultar alive() do processo remoto", error);
            return true;
        }
    }

    // CORRIGIDO ALONGAR TELA: ponto package-scoped para o fallback do helper.
    // A checagem pingBinder/permissão continua dentro do executor antes de cada processo.
    static CommandResult executeArgvForDisplay(String[] command) {
        return executeArgv(command);
    }

    private static CommandResult executeArgv(String[] command) {
        // Um único processo remoto por vez. Isso também serializa as leituras
        // de confirmação de wm/settings com aplicações e rollbacks.
        synchronized (PROCESS_LOCK) {
            return executeArgvUnlocked(command);
        }
    }

    private static CommandResult executeArgvUnlocked(String[] command) {
        lastRemoteProcessCreated = false;
        lastException = "";
        String printable = command == null ? "null" : String.join(" ", command); // CORRIGIDO BUG1: registra o comando mesmo quando a entrada é inválida.
        Log.d("SHIZUKU_DEBUG", "antes de executar: " + printable); // CORRIGIDO BUG1: log obrigatório antes de qualquer processo remoto.
        if (!isAvailable()) {
            String message = status();
            lastBridgeState = message.toLowerCase(Locale.US).contains("não iniciado")
                    ? "shizuku não iniciado" : "binder indisponível";
            recordStreams("", message);
            recordCommand("não enviado", -1, message);
            Log.e(TAG, "Comando não enviado: " + message);
            Log.e(FLOW_TAG, "SHIZUKU_COMMAND_NOT_SENT state=" + lastBridgeState); // LOG ADICIONADO
            return new CommandResult(false, -1, "", message);
        }
        if (!hasPermission()) {
            String message = status();
            lastBridgeState = "app não autorizado";
            recordStreams("", message);
            recordCommand("não enviado", -1, message);
            Log.e(TAG, "Comando não enviado: " + message);
            Log.e(FLOW_TAG, "SHIZUKU_COMMAND_NOT_SENT state=app_nao_autorizado"); // LOG ADICIONADO
            return new CommandResult(false, -1, "", message);
        }
        if (command == null || command.length == 0) {
            recordFailure("comando vazio", "Comando vazio");
            return new CommandResult(false, -1, "", "Comando vazio");
        }

        Log.d(FLOW_TAG, "SHIZUKU_REMOTE_PROCESS_CREATE command=" + printable); // LOG ADICIONADO
        ShizukuRemoteProcess process = null;
        java.io.InputStream stdoutStream = null;
        java.io.InputStream stderrStream = null;
        Thread outThread = null;
        Thread errThread = null;
        boolean remoteFinished = false;
        try {
            // API 13.1.5 retorna ShizukuRemoteProcess. Não usar os métodos
            // herdados Process.waitFor(timeout), isAlive() ou exitValue():
            // eles consultam exitValue() cedo porque a classe remota não os
            // sobrescreve e isso causa "process hasn't exited".
            process = newProcess(command);
            lastRemoteProcessCreated = true;
            Log.d(FLOW_TAG, "SHIZUKU_REMOTE_PROCESS_CREATED command=" + printable); // LOG ADICIONADO
            lastBridgeState = "processo remoto criado";
            try { process.getOutputStream().close(); }
            catch (Throwable error) { Log.w(TAG, "Falha fechando stdin remoto", error); }

            stdoutStream = process.getInputStream();
            stderrStream = process.getErrorStream();
            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            final java.io.InputStream outStreamForReader = stdoutStream;
            final java.io.InputStream errStreamForReader = stderrStream;
            outThread = new Thread(() -> copyStream(outStreamForReader, stdout), "santos-shizuku-stdout"); // CORRIGIDO BUG1: thread auxiliar apenas drena stdout e não executa comando/UI.
            errThread = new Thread(() -> copyStream(errStreamForReader, stderr), "santos-shizuku-stderr"); // CORRIGIDO BUG1: thread auxiliar apenas drena stderr e não executa comando/UI.
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            boolean finished = process.waitForTimeout(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            int exitCode;
            boolean timedOut = false;
            if (!finished) {
                // O processo pode ter terminado exatamente no limite. Dar uma
                // pequena janela para confirmar antes de enviar destroy().
                boolean finishedDuringGrace = process.waitForTimeout(250L, TimeUnit.MILLISECONDS);
                if (finishedDuringGrace) {
                    exitCode = process.waitFor();
                    remoteFinished = true;
                } else if (!remoteAlive(process)) {
                    // alive()==false é a confirmação segura; waitFor() então
                    // devolve o código real sem chamar exitValue().
                    exitCode = process.waitFor();
                    remoteFinished = true;
                } else {
                    timedOut = true;
                    Log.e(FLOW_TAG, "SHIZUKU_REMOTE_TIMEOUT command=" + printable); // LOG ADICIONADO
                    process.destroy();
                    boolean terminated = process.waitForTimeout(1_500L, TimeUnit.MILLISECONDS);
                    if (terminated) {
                        exitCode = process.waitFor();
                        remoteFinished = true;
                    } else {
                        // ShizukuRemoteProcess não oferece destroyForcibly
                        // próprio. Uma segunda solicitação normal é segura;
                        // não fingimos um exit code que não foi observado.
                        try { if (remoteAlive(process)) process.destroy(); }
                        catch (Throwable error) { Log.w(TAG, "Segundo destroy falhou", error); }
                        exitCode = -1;
                    }
                }
            } else {
                // waitForTimeout(true) é a barreira de término remoto; waitFor
                // retorna o código real da API 13.1.5.
                exitCode = process.waitFor();
                remoteFinished = true;
            }

            if (outThread != null) outThread.join(1_500L);
            if (errThread != null) errThread.join(1_500L);
            String out = stdout.toString().trim();
            String err = stderr.toString().trim();
            if (timedOut) {
                if (!err.isEmpty()) err += "\n";
                err += "Tempo limite excedido na execução remota";
            }
            recordStreams(out, err);
            lastBridgeState = exitCode == 0 ? "comando executado" : "comando recusado pelo firmware";
            String detail = (out.isEmpty() ? "" : "stdout=" + summarize(out))
                    + (err.isEmpty() ? "" : (out.isEmpty() ? "" : " · ") + "stderr=" + summarize(err));
            if (timedOut) detail = detail.isEmpty() ? "timeout" : detail;
            recordCommand(printable, exitCode,
                    detail.isEmpty() ? (exitCode == 0 ? "sucesso" : "sem saída") : detail);
            // CORRIGIDO: diagnóstico completo e estruturado solicitado para cada comando.
            // CORRIGIDO: a tag obrigatória registra stdout/stderr completos;
            // a UI também recebe os mesmos buffers por lastCommandStatus().
            Log.d("SHIZUKU_DEBUG", "resultado command=" + printable
                    + " stdout=" + out + " stderr=" + err
                    + " exit=" + exitCode);
            if (!err.isEmpty()) Log.e(TAG, "stderr (exit=" + exitCode + ") command=" + printable + " :: " + err);
            if (exitCode == 0) Log.d(TAG, "OK exit=0 command=" + printable);
            else Log.e(TAG, "FAILED exit=" + exitCode + " command=" + printable);
            Log.d(FLOW_TAG, "SHIZUKU_REMOTE_RESULT exit=" + exitCode
                    + " stdoutChars=" + out.length() + " stderrChars=" + err.length()); // LOG ADICIONADO
            Log.d("SHIZUKU_DEBUG", "depois de executar: " + printable + " exit=" + exitCode); // CORRIGIDO BUG1: log obrigatório após o comando remoto.
            return new CommandResult(exitCode == 0, exitCode, out, err);
        } catch (Throwable error) {
            String detail = error.getClass().getSimpleName() + ": "
                    + (error.getMessage() == null ? "falha desconhecida" : error.getMessage());
            lastException = detail;
            lastBridgeState = lastRemoteProcessCreated
                    ? "exceção durante processo remoto" : "processo remoto não criado";
            recordStreams("", detail);
            recordCommand(printable, -1, detail);
            Log.e(TAG, "Falha de conexão/execução command=" + printable, error);
            Log.e(FLOW_TAG, "SHIZUKU_REMOTE_EXCEPTION command=" + printable, error); // LOG ADICIONADO
            Log.e("SHIZUKU_DEBUG", "erro: " + error, error); // CORRIGIDO BUG1: log obrigatório da exceção remota.
            return new CommandResult(false, -1, "", detail);
        } finally {
            // Só destruir no caminho em que a criação/espera falhou e o remoto
            // ainda está vivo. Nunca chamar exitValue/isAlive em Process.
            if (process != null && !remoteFinished) {
                try { if (remoteAlive(process)) process.destroy(); }
                catch (Throwable error) { Log.w(TAG, "Falha finalizando processo remoto", error); }
            }
            try { if (stdoutStream != null) stdoutStream.close(); }
            catch (Throwable error) { Log.w(TAG, "Falha fechando stdout remoto", error); }
            try { if (stderrStream != null) stderrStream.close(); }
            catch (Throwable error) { Log.w(TAG, "Falha fechando stderr remoto", error); }
            try { if (process != null) process.getOutputStream().close(); }
            catch (Throwable error) { Log.w(TAG, "Falha fechando stdin remoto", error); }
            try { if (outThread != null) outThread.join(300L); }
            catch (Throwable error) { Log.w(TAG, "Falha aguardando stdout remoto", error); }
            try { if (errThread != null) errThread.join(300L); }
            catch (Throwable error) { Log.w(TAG, "Falha aguardando stderr remoto", error); }
        }
    }

    // ADICIONADO: resumo curto para o bloco de status e para diagnóstico sem floodar a UI.
    private static String summarize(String value) {
        // CORRIGIDO: usar o helper Java explícito, não a extensão Kotlin take().
        return take(value == null ? "" : value.replaceAll("\\s+", " ").trim(), 240);
    }

    // ADICIONADO: Kotlin não pode ser usado neste arquivo Java; limite o texto manualmente.
    private static String take(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private static void recordStreams(String stdout, String stderr) {
        // CORRIGIDO: preservar os buffers completos evita truncar stdout/stderr
        // antes de a aba LOG renderizar o diagnóstico.
        lastStdout = stdout == null ? "" : stdout;
        lastStderr = stderr == null ? "" : stderr;
    }

    // Grava somente um resumo compacto para o cabeçalho; a área Detalhes usa
    // os streams completos limitados para não ocupar a overlay inteira.
    private static void recordCommand(String command, int exitCode, String detail) {
        lastCommand = command == null ? "desconhecido" : command;
        lastExitCode = exitCode;
        lastCommandResult = detail == null ? "" : take(detail, 240);
    }

    private static void recordFailure(String command, String detail) {
        lastBridgeState = "execução recusada antes do processo remoto";
        lastRemoteProcessCreated = false;
        lastException = detail == null ? "" : detail;
        recordStreams("", detail);
        recordCommand(command, -1, detail);
    }

    public static String lastCommandStatus() {
        String exit = lastExitCode == Integer.MIN_VALUE ? "—" : String.valueOf(lastExitCode);
        return "Último comando: " + lastCommand + " · exit code: " + exit
                + " · " + lastCommandResult
                + "\nstdout: " + (lastStdout.isEmpty() ? "(vazio)" : lastStdout)
                + "\nstderr: " + (lastStderr.isEmpty() ? "(vazio)" : lastStderr);
    }

    public static String diagnosticStatus(Context context) {
        String overlay = Build.VERSION.SDK_INT < 23 ||
                android.provider.Settings.canDrawOverlays(context) ? "concedida" : "pendente";
        String exit = lastExitCode == Integer.MIN_VALUE ? "—" : String.valueOf(lastExitCode);
        return "Shizuku: " + status() + "\n"
                + "Overlay: " + overlay + "\n"
                + "Estado da ponte: " + lastBridgeState + "\n"
                + "Processo remoto criado: " + (lastRemoteProcessCreated ? "sim" : "não") + "\n"
                + "Comando: " + lastCommand + "\n"
                + "Exit code: " + exit + "\n"
                + "Stdout: " + (lastStdout.isEmpty() ? "(vazio)" : lastStdout) + "\n"
                + "Stderr: " + (lastStderr.isEmpty() ? "(vazio)" : lastStderr) + "\n"
                + "Resultado: " + (lastCommandResult.isEmpty() ? "(vazio)" : lastCommandResult) + "\n"
                + "Exceção: " + (lastException.isEmpty() ? "(nenhuma)" : lastException);
    }

    /**
     * Valida somente a ponte mínima. O primeiro processo obrigatório é o echo;
     * leituras de display/settings não ficam no caminho do botão e não podem
     * fazer a UI parecer travada.
     */
    public static void testBridge(final Context context, final Callback callback) {
        ShizukuManager.launchIo("santos-shizuku-bridge-test", () -> { // CORRIGIDO BUG1: teste da ponte roda fora da Main Thread.
            try {
                if (!hasPermission()) {
                    dispatch(callback, false, status());
                    return;
                }
                // O primeiro e único teste de validação é deliberadamente sem
                // sh -c: stdout esperado + exit code 0 confirmam a ponte.
                CommandResult echo = executeArgv(new String[]{"echo", "SANTOS_SHIZUKU_OK"});
                Log.d(FLOW_TAG, "SHIZUKU_ECHO_RESULT exit=" + echo.exitCode
                        + " stdout=" + echo.stdout
                        + " stderr=" + echo.stderr);
                if (!echo.ok || !echo.stdout.contains("SANTOS_SHIZUKU_OK")) {
                    dispatch(callback, false, "echo SANTOS_SHIZUKU_OK falhou: exit="
                            + echo.exitCode + " stdout=" + echo.stdout
                            + " stderr=" + echo.stderr);
                    return;
                }
                dispatch(callback, true, "echo SANTOS_SHIZUKU_OK confirmado: exit=0"
                        + " stdout=" + echo.stdout
                        + " stderr=" + echo.stderr);
            } catch (Throwable error) {
                String detail = error.getClass().getSimpleName() + ": "
                        + (error.getMessage() == null ? "falha no diagnóstico" : error.getMessage());
                lastException = detail;
                lastBridgeState = "exceção no diagnóstico";
                Log.e(TAG, "Exceção fora do executor em testBridge", error);
                dispatch(callback, false, detail);
            }
        }); // CORRIGIDO BUG1: encerra o worker protegido do teste.
    }

    // ADICIONADO: entrega callback na Main Looper para proteger Views dos consumidores.
    private static void dispatch(Callback callback, boolean ok, String message) {
        if (callback == null) return;
        MAIN_HANDLER.post(() -> {
            try {
                callback.onFinished(ok, message);
            } catch (Throwable error) {
                recordCallbackFailure("CommandCallback", error);
            }
        });
    }

    // Callback de UI também é fronteira de falha: registrar a stack trace
    // evita que uma exceção de View derrube o processo inteiro sem diagnóstico.
    private static void recordCallbackFailure(String callbackName, Throwable error) {
        String detail = error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "callback falhou" : error.getMessage());
        lastException = detail;
        lastBridgeState = "callback lançou exceção";
        recordCommand(lastCommand, lastExitCode, detail);
        Log.e(TAG, "Exceção no callback " + callbackName, error);
    }

    // ADICIONADO: variante para leitura de display, também sempre na Main Looper.
    private static void dispatch(DisplayInfoCallback callback, boolean ok, String message,
                                 DisplayInfo info) {
        if (callback == null) return;
        MAIN_HANDLER.post(() -> {
            try {
                callback.onFinished(ok, message, info);
            } catch (Throwable error) {
                recordCallbackFailure("DisplayInfoCallback", error);
            }
        });
    }

    private static String runForOutput(String[] command) {
        CommandResult result = executeArgv(command);
        return result.ok ? result.stdout : "";
    }

    private static boolean run(String[] command) {
        return executeArgv(command).ok;
    }

    /**
     * Settings protegidos são enviados uma única vez pelo UID remoto autorizado.
     * A causa real (stdout, stderr e exit code) permanece no diagnóstico; não há
     * tentativa especulativa de conceder permissões protegidas ao pacote.
     */
    private static boolean runSetting(Context context, String[] command) {
        return executeArgv(command).ok;
    }

    private static boolean validNumber(String value) {
        return value != null && value.matches("-?\\d+(\\.\\d+)?");
    }

    private static boolean validSize(String value) {
        return value != null && value.matches("\\d{1,6}x\\d{1,6}");
    }

    private static String settingValue(Context context, String key, String fallback) {
        return context.getSharedPreferences("santos_session", Context.MODE_PRIVATE)
                .getString(key, fallback);
    }

    private static void saveOriginalSettingsIfNeeded(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (!prefs.contains("original_long_press_timeout")) {
            String value = readSetting(new String[]{"settings", "get", "secure", "long_press_timeout"});
            if (validNumber(value)) { editor.putString("original_long_press_timeout", value); changed = true; }
        }
        if (!prefs.contains("original_window_animation_scale")) {
            String value = readSetting(new String[]{"settings", "get", "global", "window_animation_scale"});
            if (validNumber(value)) { editor.putString("original_window_animation_scale", value); changed = true; }
        }
        if (!prefs.contains("original_transition_animation_scale")) {
            String value = readSetting(new String[]{"settings", "get", "global", "transition_animation_scale"});
            if (validNumber(value)) { editor.putString("original_transition_animation_scale", value); changed = true; }
        }
        if (!prefs.contains("original_animator_duration_scale")) {
            String value = readSetting(new String[]{"settings", "get", "global", "animator_duration_scale"});
            if (validNumber(value)) { editor.putString("original_animator_duration_scale", value); changed = true; }
        }
        if (changed) editor.apply();
    }

    private static String readSetting(String[] command) {
        try {
            String output = runForOutput(command).trim();
            if (validNumber(output)) return output;
            // Um setting sem valor pode ser legítimo; ele não vira estado
            // original numérico por acidente.
            Log.w(TAG, "Setting não numérico: " + String.join(" ", command) + " :: " + output);
        } catch (Throwable error) {
            Log.e(TAG, "Falha lendo setting " + String.join(" ", command), error);
        }
        return "";
    }

    private static boolean settingMatches(String namespace, String name, String expected) {
        String actual = readSetting(new String[]{"settings", "get", namespace, name});
        if (!validNumber(actual) || !validNumber(expected)) return false;
        try {
            return Math.abs(Double.parseDouble(actual) - Double.parseDouble(expected)) <= 0.01d;
        } catch (NumberFormatException error) {
            return false;
        }
    }

    private static boolean putSetting(String namespace, String name, String value) throws Exception {
        if (!validNumber(value)) return false;
        return run(new String[]{"settings", "put", namespace, name, value});
    }

    private static boolean restoreSetting(String namespace, String name, String value) throws Exception {
        return putSetting(namespace, name, value);
    }

    // CORRIGIDO: mantém o comando de rollback associado ao comando aplicado,
    // permitindo desfazer alterações parciais quando uma sequência falha.
    private static void addReversibleSetting(List<String[]> commands,
                                             List<String[]> rollback,
                                             String namespace, String name,
                                             String value, String original) {
        if (!validNumber(original)) return;
        commands.add(new String[]{"settings", "put", namespace, name, value});
        rollback.add(new String[]{"settings", "put", namespace, name, original});
    }

    private static final class LocalSettingsResult {
        final boolean ok;
        final String message;
        LocalSettingsResult(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    private static boolean hasSecureSettings(Context context) {
        return context != null && context.checkSelfPermission(
                "android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED;
    }

    private static String localSetting(String namespace, String name, Context context) {
        try {
            String value = "secure".equals(namespace)
                    ? Settings.Secure.getString(context.getContentResolver(), name)
                    : Settings.Global.getString(context.getContentResolver(), name);
            Log.d("SYS_READ_DEBUG", namespace + "." + name + "=" + value);
            return value == null ? "" : value;
        } catch (Throwable error) {
            Log.e("SYS_READ_DEBUG", namespace + "." + name + " read failed", error);
            return "";
        }
    }

    private static boolean localPutSetting(String namespace, String name, String value, Context context) {
        try {
            boolean ok = "secure".equals(namespace)
                    ? Settings.Secure.putString(context.getContentResolver(), name, value)
                    : Settings.Global.putString(context.getContentResolver(), name, value);
            String readback = localSetting(namespace, name, context);
            boolean confirmed = ok && value.equals(readback);
            Log.d("SYS_WRITE_DEBUG", namespace + "." + name + " requested=" + value
                    + " confirmed=" + readback + " ok=" + confirmed);
            return confirmed;
        } catch (Throwable error) {
            Log.e("SYS_WRITE_DEBUG", namespace + "." + name + " write failed", error);
            return false;
        }
    }

    private static LocalSettingsResult applySafeSettingsLocal(Context context, boolean touch,
                                                               boolean system, int longPress,
                                                               int animationPercent) {
        SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
        String press = localSetting("secure", "long_press_timeout", context);
        String window = localSetting("global", "window_animation_scale", context);
        String transition = localSetting("global", "transition_animation_scale", context);
        String animator = localSetting("global", "animator_duration_scale", context);
        if (!validNumber(press) || !validNumber(window) || !validNumber(transition) || !validNumber(animator)) {
            return new LocalSettingsResult(false, "WRITE_SECURE_SETTINGS concedida, mas um setting não foi lido");
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains("original_long_press_timeout")) editor.putString("original_long_press_timeout", press);
        if (!prefs.contains("original_window_animation_scale")) editor.putString("original_window_animation_scale", window);
        if (!prefs.contains("original_transition_animation_scale")) editor.putString("original_transition_animation_scale", transition);
        if (!prefs.contains("original_animator_duration_scale")) editor.putString("original_animator_duration_scale", animator);
        editor.apply();
        String targetPress = touch ? String.valueOf(Math.max(50, Math.min(1000, longPress))) : press;
        String targetAnimation = String.format(Locale.US, "%.2f", Math.max(0, Math.min(1000, animationPercent)) / 100f);
        String targetWindow = system ? targetAnimation : window;
        String targetTransition = system ? targetAnimation : transition;
        String targetAnimator = system ? targetAnimation : animator;
        String[][] values = {
                {"secure", "long_press_timeout", targetPress},
                {"global", "window_animation_scale", targetWindow},
                {"global", "transition_animation_scale", targetTransition},
                {"global", "animator_duration_scale", targetAnimator}
        };
        int applied = 0;
        for (String[] value : values) {
            if (!localPutSetting(value[0], value[1], value[2], context)) {
                boolean rollback = true;
                for (int i = applied; i >= 0; i--) {
                    String original = "secure".equals(values[i][0]) ? press
                            : "window_animation_scale".equals(values[i][1]) ? window
                            : "transition_animation_scale".equals(values[i][1]) ? transition : animator;
                    rollback &= localPutSetting(values[i][0], values[i][1], original, context);
                }
                return new LocalSettingsResult(false, "Ajuste local não confirmado; rollback=" + rollback);
            }
            applied++;
        }
        return new LocalSettingsResult(true, "Ajustes locais confirmados por WRITE_SECURE_SETTINGS; sem internet");
    }

    private static LocalSettingsResult restoreSafeSettingsLocal(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
        String[][] values = {
                {"secure", "long_press_timeout", prefs.getString("original_long_press_timeout", "")},
                {"global", "window_animation_scale", prefs.getString("original_window_animation_scale", "")},
                {"global", "transition_animation_scale", prefs.getString("original_transition_animation_scale", "")},
                {"global", "animator_duration_scale", prefs.getString("original_animator_duration_scale", "")}
        };
        boolean attempted = false;
        for (String[] value : values) {
            if (!validNumber(value[2])) continue;
            attempted = true;
            if (!localPutSetting(value[0], value[1], value[2], context)) {
                return new LocalSettingsResult(false, "Restauração local de " + value[1] + " não confirmada");
            }
        }
        if (attempted) prefs.edit().remove("original_long_press_timeout")
                .remove("original_window_animation_scale")
                .remove("original_transition_animation_scale")
                .remove("original_animator_duration_scale").apply();
        return new LocalSettingsResult(true, attempted
                ? "Ajustes locais originais restaurados e confirmados" : "Nenhum ajuste local salvo");
    }

    public static void applySafeSettings(final Context context, final boolean touch,
                                         final boolean system, final int longPress,
                                         final int animationPercent, final Callback callback) {
        final int press = Math.max(50, Math.min(1000, longPress));
        final String animation = String.format(Locale.US, "%.2f",
                Math.max(0, Math.min(1000, animationPercent)) / 100f);
        ShizukuManager.launchIo("santos-shizuku-settings", () -> { // CORRIGIDO BUG1: settings remotos rodam em Coroutine IO.
            try {
                if (hasSecureSettings(context)) {
                    LocalSettingsResult local = applySafeSettingsLocal(context, touch, system,
                            press, animationPercent);
                    dispatch(callback, local.ok, local.message);
                    return;
                }
                // CORRIGIDO: consultar autorização no worker evita snapshot stale
                // da Main Thread logo após o usuário aprovar no Shizuku.
                if (!hasPermission()) {
                    dispatch(callback, false, status());
                    return;
                }
                saveOriginalSettingsIfNeeded(context);
                SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
                List<String[]> commands = new ArrayList<>();
                List<String[]> rollback = new ArrayList<>();
                String pressOriginal = prefs.getString("original_long_press_timeout", "");
                String window = prefs.getString("original_window_animation_scale", "");
                String transition = prefs.getString("original_transition_animation_scale", "");
                String animator = prefs.getString("original_animator_duration_scale", "");
                if (touch && !validNumber(pressOriginal)) {
                    dispatch(callback, false,
                            "Touch não suportado: long_press_timeout não foi exposto.");
                    return;
                }
                if (system && (!validNumber(window) || !validNumber(transition)
                        || !validNumber(animator))) {
                    dispatch(callback, false,
                            "Escalas de animação não suportadas neste aparelho.");
                    return;
                }
                if (touch) {
                    addReversibleSetting(commands, rollback, "secure", "long_press_timeout",
                            String.valueOf(press), pressOriginal);
                } else {
                    addReversibleSetting(commands, rollback, "secure", "long_press_timeout",
                            pressOriginal, pressOriginal);
                }
                if (system) {
                    addReversibleSetting(commands, rollback, "global", "window_animation_scale",
                            animation, window);
                    addReversibleSetting(commands, rollback, "global", "transition_animation_scale",
                            animation, transition);
                    addReversibleSetting(commands, rollback, "global", "animator_duration_scale",
                            animation, animator);
                } else {
                    addReversibleSetting(commands, rollback, "global", "window_animation_scale",
                            window, window);
                    addReversibleSetting(commands, rollback, "global", "transition_animation_scale",
                            transition, transition);
                    addReversibleSetting(commands, rollback, "global", "animator_duration_scale",
                            animator, animator);
                }
                for (int i = 0; i < commands.size(); i++) {
                    if (!runSetting(context, commands.get(i))) {
                        boolean rolledBack = true;
                        for (int j = i; j >= 0; j--) {
                            if (!runSetting(context, rollback.get(j))) rolledBack = false;
                        }
                        dispatch(callback, false, rolledBack
                                ? "O Android recusou um ajuste; alterações parciais foram desfeitas."
                                : "O Android recusou um ajuste e o rollback não foi concluído.");
                        return;
                    }
                }
                boolean confirmed = true;
                if (validNumber(pressOriginal)) {
                    confirmed &= settingMatches("secure", "long_press_timeout",
                            touch ? String.valueOf(press) : pressOriginal);
                }
                if (validNumber(window)) {
                    confirmed &= settingMatches("global", "window_animation_scale",
                            system ? animation : window);
                }
                if (validNumber(transition)) {
                    confirmed &= settingMatches("global", "transition_animation_scale",
                            system ? animation : transition);
                }
                if (validNumber(animator)) {
                    confirmed &= settingMatches("global", "animator_duration_scale",
                            system ? animation : animator);
                }
                if (!confirmed) {
                    boolean rolledBack = true;
                    for (int j = rollback.size() - 1; j >= 0; j--) {
                        if (!runSetting(context, rollback.get(j))) rolledBack = false;
                    }
                    dispatch(callback, false, rolledBack
                            ? "O Android não confirmou os ajustes; rollback concluído."
                            : "O Android não confirmou os ajustes; rollback também falhou.");
                    return;
                }
                dispatch(callback, true, commands.isEmpty()
                        ? "Nenhum ajuste Shizuku selecionado."
                        : "Ajustes autorizados pelo Shizuku aplicados e confirmados.");
            } catch (Throwable e) {
                Log.e(TAG, "Falha aplicando ajustes autorizados", e);
                dispatch(callback, false, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "falha ao aplicar ajustes" : e.getMessage()));
            }
        }); // CORRIGIDO BUG1: encerra a coroutine de settings.
    }

    public static void restoreSafeSettings(final Context context, final Callback callback) {
        ShizukuManager.launchIo("santos-shizuku-restore", () -> { // CORRIGIDO BUG1: restauração não bloqueia o loop principal.
            try {
                if (hasSecureSettings(context)) {
                    LocalSettingsResult local = restoreSafeSettingsLocal(context);
                    dispatch(callback, local.ok, local.message);
                    return;
                }
                // CORRIGIDO: autorização é confirmada no mesmo worker que restaura.
                if (!hasPermission()) {
                    dispatch(callback, false, status());
                    return;
                }
                SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
                String[][] values = {
                        {"secure", "long_press_timeout", prefs.getString("original_long_press_timeout", "")},
                        {"global", "window_animation_scale", prefs.getString("original_window_animation_scale", "")},
                        {"global", "transition_animation_scale", prefs.getString("original_transition_animation_scale", "")},
                        {"global", "animator_duration_scale", prefs.getString("original_animator_duration_scale", "")}
                };
                boolean attempted = false;
                for (String[] value : values) {
                    if (!validNumber(value[2])) continue;
                    attempted = true;
                    if (!restoreSetting(value[0], value[1], value[2])) {
                        // CORRIGIDO: não esconder qual sequência de restauração falhou.
                        dispatch(callback, false, "Não foi possível restaurar todos os ajustes; "
                                + lastCommandStatus());
                        return;
                    }
                }
                prefs.edit().remove("original_long_press_timeout")
                        .remove("original_window_animation_scale")
                        .remove("original_transition_animation_scale")
                        .remove("original_animator_duration_scale")
                        .apply();
                dispatch(callback, true, attempted
                        ? "Ajustes originais restaurados."
                        : "Nenhum ajuste de sistema salvo para restaurar.");
            } catch (Throwable e) {
                // CORRIGIDO: Logcat e UI recebem a exceção de restauração.
                Log.e(TAG, "Falha restaurando ajustes autorizados", e);
                dispatch(callback, false, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "falha ao restaurar ajustes" : e.getMessage()));
            }
        }); // CORRIGIDO BUG1: encerra a coroutine de restauração.
    }

    private static final class RawDisplayState {
        final String physicalSize;
        final String overrideSize;
        final String physicalDensity;
        final String overrideDensity;
        // ADICIONADO: tamanho efetivo considera override quando presente.
        final String currentSize;
        // ADICIONADO: densidade efetiva considera override quando presente.
        final int currentDensity;
        // ADICIONADO: rotação atual lida sem inventar offsets VSync.
        final int rotation;
        // ADICIONADO: capability real de overscan do firmware.
        final boolean overscanSupported;
        // ADICIONADO: valor original de overscan quando o comando expõe leitura.
        final String overscan;

        RawDisplayState(String physicalSize, String overrideSize,
                        String physicalDensity, String overrideDensity,
                        int rotation, boolean overscanSupported, String overscan) {
            this.physicalSize = physicalSize;
            this.overrideSize = overrideSize;
            this.physicalDensity = physicalDensity;
            this.overrideDensity = overrideDensity;
            this.currentSize = overrideSize.isEmpty() ? physicalSize : overrideSize;
            this.currentDensity = parsePositiveInt(overrideDensity) > 0
                    ? parsePositiveInt(overrideDensity) : parsePositiveInt(physicalDensity);
            this.rotation = rotation;
            this.overscanSupported = overscanSupported;
            this.overscan = overscan == null ? "" : overscan;
        }
    }

    private static RawDisplayState readRawDisplayState() throws Exception {
        // CORRIGIDO: cada leitura exige exit code 0 e preserva a causa real da falha.
        CommandResult sizeResult = executeArgv(new String[]{"wm", "size"});
        if (!sizeResult.ok) throw new IllegalStateException("wm size exit="
                + sizeResult.exitCode + " " + summarize(sizeResult.output));
        // CORRIGIDO: density não é assumido a partir da resolução física.
        CommandResult densityResult = executeArgv(new String[]{"wm", "density"});
        if (!densityResult.ok) throw new IllegalStateException("wm density exit="
                + densityResult.exitCode + " " + summarize(densityResult.output));
        // ADICIONADO: rotação é somente informativa/orientadora, sem offsets inventados.
        CommandResult displayResult = executeArgv(new String[]{"dumpsys", "display"});
        int rotation = displayResult.ok ? extractRotation(displayResult.stdout) : -1;
        /*
         * Não sondar o subcomando legado de offsets em toda leitura. Em
         * Androids atuais ele não existe e resolução/DPI não dependem dele.
         */
        String overscan = "";
        boolean overscanSupported = false;
        return new RawDisplayState(
                extract(sizeResult.stdout, "Physical size:"),
                extract(sizeResult.stdout, "Override size:"),
                extract(densityResult.stdout, "Physical density:"),
                extract(densityResult.stdout, "Override density:"),
                rotation, overscanSupported, overscan);
    }

    private static int parsePositiveInt(String value) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value);
            return parsed > 0 ? parsed : 0;
        } catch (Exception ignored) { return 0; }
    }

    private static int[] parseSize(String value) {
        if (!validSize(value)) return new int[]{0, 0};
        String[] parts = value.split("x", 2);
        return new int[]{parsePositiveInt(parts[0]), parsePositiveInt(parts[1])};
    }

    private static boolean validDisplayProfile(int width, int height, int density,
                                               String physicalSize) {
        int[] physical = parseSize(physicalSize);
        // CORRIGIDO: aceitar também a orientação rotacionada. Em paisagem,
        // wm size pode expor 1080x2400 enquanto a UI monta 2400x1080.
        boolean fitsNormal = width <= physical[0] && height <= physical[1];
        boolean fitsRotated = width <= physical[1] && height <= physical[0];
        return physical[0] >= 320 && physical[1] >= 320
                && width >= 320 && height >= 320
                && (fitsNormal || fitsRotated)
                && density >= 120 && density <= 640;
    }

    private static void saveOriginalDisplayIfNeeded(SharedPreferences prefs,
                                                     RawDisplayState state) {
        // CORRIGIDO: cada recurso tem sua própria chave e nunca sobrescreve o original.
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        if (!prefs.contains("original_display_size")) {
            String size = state.overrideSize.isEmpty() ? "reset" : state.overrideSize;
            editor.putString("original_display_size", size);
            changed = true;
        }
        if (!prefs.contains("original_display_density")) {
            String density = state.overrideDensity.isEmpty() ? "reset" : state.overrideDensity;
            editor.putString("original_display_density", density);
            changed = true;
        }
        // ADICIONADO: overscan original só é guardado quando o firmware o expõe.
        if (state.overscanSupported && !prefs.contains("original_display_overscan")) {
            editor.putString("original_display_overscan", state.overscan);
            changed = true;
        }
        if (changed) editor.apply();
    }

    public static void readDisplayInfo(final DisplayInfoCallback callback) {
        ShizukuManager.launchIo("santos-display-info", () -> { // CORRIGIDO BUG1: leitura de wm/density roda em Coroutine IO.
            try {
                // CORRIGIDO: não rejeitar pela cópia de permissão da Main Thread;
                // a autorização é consultada no worker antes de wm/density.
                if (!hasPermission()) {
                    dispatch(callback, false, status(), null);
                    return;
                }
                RawDisplayState state = readRawDisplayState();
                DisplayInfo info = new DisplayInfo(state.physicalSize, state.overrideSize,
                        parsePositiveInt(state.physicalDensity),
                        parsePositiveInt(state.overrideDensity), state.rotation,
                        state.overscanSupported, state.overscan);
                // CORRIGIDO: callback de leitura também chega na thread principal.
                dispatch(callback, true, "Estado oficial da tela lido.", info);
            } catch (Throwable e) {
                // CORRIGIDO: stderr/exceção real deixa de ser substituído por mensagem falsa.
                Log.e(TAG, "Falha lendo modos oficiais da tela", e);
                dispatch(callback, false, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "falha ao ler wm" : e.getMessage()), null);
            }
        }); // CORRIGIDO BUG1: encerra a coroutine de leitura de display.
    }

    public static void applyDisplayProfile(final Context context, final int width,
                                           final int height, final int density,
                                           final boolean stretch, final Callback callback) {
        if (context == null) {
            dispatch(callback, false, "Context inválido para aplicar o perfil de tela");
            return;
        }
        ShizukuManager.launchIo("santos-display-apply", () -> { // CORRIGIDO BUG1: aplicação de wm roda em Coroutine IO com contexto validado.
            final SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
            try {
                // CORRIGIDO: a permissão real é lida no worker e não no snapshot
                // visual, evitando cancelar wm size/density logo após autorizar.
                if (!hasPermission()) {
                    dispatch(callback, false, status());
                    return;
                }
                RawDisplayState state = readRawDisplayState();
                if (!validDisplayProfile(width, height, density, state.physicalSize)) {
                    dispatch(callback, false, "Resolução ou DPI fora dos limites seguros do display.");
                    return;
                }
                saveOriginalDisplayIfNeeded(prefs, state);
                String targetSize = width + "x" + height;
                // CORRIGIDO: confirmar size imediatamente após o comando, aceitando ambas as rotações.
                CommandResult sizeResult = executeArgv(new String[]{"wm", "size", targetSize});
                RawDisplayState afterSize = sizeResult.ok ? readRawDisplayState() : state;
                if (!sizeResult.ok || !sameSize(targetSize, afterSize.currentSize)) {
                    boolean rolledBack = restoreSavedDisplayTokens(prefs);
                    dispatch(callback, false, "wm size falhou/ não confirmou (exit="
                            + sizeResult.exitCode + "); rollback=" + rolledBack + "; "
                            + lastCommandStatus());
                    return;
                }
                // CORRIGIDO: density só é enviado quando difere do valor efetivo lido.
                CommandResult densityResult = afterSize.currentDensity == density
                        ? new CommandResult(true, 0, "", "")
                        : executeArgv(new String[]{"wm", "density", String.valueOf(density)});
                RawDisplayState finalState = densityResult.ok ? readRawDisplayState() : afterSize;
                if (!densityResult.ok || !sameSize(targetSize, finalState.currentSize)
                        || finalState.currentDensity != density) {
                    boolean rolledBack = restoreSavedDisplayTokens(prefs);
                    dispatch(callback, false, "wm density não confirmou (exit="
                            + densityResult.exitCode + "); rollback=" + rolledBack + "; "
                            + lastCommandStatus());
                    return;
                }
                prefs.edit().putString("applied_display_size", targetSize)
                        .putInt("applied_display_density", density)
                        .putBoolean("stretch_requested", stretch).apply();
                dispatch(callback, true, (stretch
                        ? "Resolução/DPI aplicados e verificados; corte lateral solicitado."
                        : "Resolução/DPI aplicados e verificados.") + " · " + lastCommandStatus());
            } catch (Throwable e) {
                // CORRIGIDO: rollback também ocorre quando a exceção acontece
                // depois de wm size/density já terem sido enviados.
                boolean rolledBack = restoreSavedDisplayTokens(prefs); // TRY/CATCH ADICIONADO
                Log.e(TAG, "Falha aplicando perfil de tela; rollback=" + rolledBack, e);
                dispatch(callback, false, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "falha ao aplicar perfil" : e.getMessage())
                        + "; rollback=" + rolledBack);
            }
        }); // CORRIGIDO BUG1: encerra a coroutine de aplicação de display.
    }

    private static boolean restoreSavedDisplayTokens(SharedPreferences prefs) {
        String size = prefs.getString("original_display_size", "");
        String density = prefs.getString("original_display_density", "");
        if (!("reset".equals(size) || validSize(size))) return false;
        if (!("reset".equals(density) || validNumber(density))) return false;
        try {
            // CORRIGIDO: size é restaurado primeiro e só então density.
            CommandResult sizeResult = executeArgv(new String[]{"wm", "size", size});
            boolean sizeOk = sizeResult.ok;
            // CORRIGIDO: não alterar o DPI se a resolução já falhou.
            CommandResult densityResult = sizeOk
                    ? executeArgv(new String[]{"wm", "density", density})
                    : new CommandResult(false, -1, "", "size rollback falhou");
            boolean densityOk = densityResult.ok;
            // ADICIONADO: overscan só volta se o app salvou um valor suportado.
            boolean overscanOk = densityOk && restoreOverscanIfSaved(prefs);
            RawDisplayState verified = (sizeOk && densityOk) ? readRawDisplayState() : null;
            boolean verifiedSize = verified != null && ("reset".equals(size)
                    || sameSize(size, verified.currentSize));
            boolean verifiedDensity = verified != null && ("reset".equals(density)
                    || parsePositiveInt(density) == verified.currentDensity);
            return sizeOk && densityOk && overscanOk && verifiedSize && verifiedDensity;
        } catch (Throwable error) {
            // CORRIGIDO: rollback não pode ocultar BadToken/RemoteException/erro de shell.
            Log.e(TAG, "Falha no rollback da tela", error);
            return false;
        }
    }

    // CORRIGIDO ALONGAR TELA: o comando legado de offsets foi removido do
    // Android 11+ e não participa mais de nenhuma operação. Mantemos a API
    // somente para que
    // chamadas antigas recebam uma falha explícita, nunca um falso sucesso.
    public static void resetOverscan(final Context context, final Callback callback) {
        dispatch(callback, false, "Overscan não suportado; use Reflection IWindowManager");
    }

    public static void captureDisplayState(final Context context, final Callback callback) {
        ShizukuManager.launchIo("santos-display-capture", () -> { // CORRIGIDO BUG1: captura remota roda em Coroutine IO.
            try {
                // CORRIGIDO: evitar rejeição pelo snapshot da Main Thread.
                if (!hasPermission()) {
                    dispatch(callback, false, status());
                    return;
                }
                RawDisplayState state = readRawDisplayState();
                SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
                saveOriginalDisplayIfNeeded(prefs, state);
                dispatch(callback, true, "Estado original de resolução/densidade/overscan salvo; rotação="
                        + state.rotation + ".");
            } catch (Throwable e) {
                Log.e(TAG, "Falha capturando estado oficial da tela", e);
                dispatch(callback, false, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "falha ao ler estado" : e.getMessage()));
            }
        }); // CORRIGIDO BUG1: encerra a coroutine de captura.
    }

    public static void restoreDisplayState(final Context context, final Callback callback) {
        ShizukuManager.launchIo("santos-display-restore", () -> { // CORRIGIDO BUG1: restauração de display roda em Coroutine IO.
            try {
                // CORRIGIDO: autorização é consultada no worker antes do rollback.
                if (!hasPermission()) {
                    dispatch(callback, false, status());
                    return;
                }
                SharedPreferences prefs = context.getSharedPreferences("santos_session", Context.MODE_PRIVATE);
                String size = settingValue(context, "original_display_size", "");
                String density = settingValue(context, "original_display_density", "");
                if (!("reset".equals(size) || validSize(size))
                        || !("reset".equals(density) || validNumber(density))) {
                    dispatch(callback, false, "Nenhum estado original de tela foi salvo.");
                    return;
                }
                // CORRIGIDO: size é restaurado antes de density e ambos são verificados depois.
                boolean restored = restoreSavedDisplayTokens(prefs);
                RawDisplayState verified = restored ? readRawDisplayState() : null;
                boolean sizeVerified = verified != null && ("reset".equals(size)
                        || sameSize(size, verified.currentSize));
                boolean densityVerified = verified != null && ("reset".equals(density)
                        || parsePositiveInt(density) == verified.currentDensity);
                if (!restored || !sizeVerified || !densityVerified) {
                    dispatch(callback, false, "Restauração recusada ou não confirmada; "
                            + lastCommandStatus());
                    return;
                }
                prefs.edit().remove("original_display_size")
                        .remove("original_display_density")
                        .remove("original_display_overscan")
                        .remove("applied_display_size")
                        .remove("applied_display_density")
                        .remove("stretch_requested")
                        .remove("stretch_factor").apply();
                dispatch(callback, true, "Resolução, densidade e overscan original restaurados e verificados.");
            } catch (Throwable e) {
                Log.e(TAG, "Falha restaurando tela", e);
                dispatch(callback, false, e.getClass().getSimpleName() + ": "
                        + (e.getMessage() == null ? "falha ao restaurar tela" : e.getMessage()));
            }
        }); // CORRIGIDO BUG1: encerra a coroutine de restauração de display.
    }

    private static String extract(String output, String label) {
        Matcher matcher = Pattern.compile(Pattern.quote(label) + "\\s*([0-9]{3,5}(?:x[0-9]{3,5})?)")
                .matcher(output == null ? "" : output);
        return matcher.find() ? matcher.group(1) : "";
    }

    // ADICIONADO: aceita as formas de rotação que dumpsys display usa em versões diferentes.
    private static int extractRotation(String output) {
        String text = output == null ? "" : output;
        Matcher named = Pattern.compile("(?i)ROTATION_(0|90|180|270)").matcher(text);
        if (named.find()) return Integer.parseInt(named.group(1)) / 90;
        Matcher numeric = Pattern.compile("(?i)(?:mCurrentRotation|mCurrentOrientation)\\s*[=:]\\s*(\\d)")
                .matcher(text);
        return numeric.find() ? parsePositiveInt(numeric.group(1)) : -1;
    }

    // ADICIONADO: overscan é reconhecido somente quando há quatro inteiros explícitos.
    private static String extractOverscan(String output) {
        Matcher matcher = Pattern.compile("(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*,\\s*(-?\\d+)")
                .matcher(output == null ? "" : output);
        return matcher.find() ? matcher.group(1) + "," + matcher.group(2) + ","
                + matcher.group(3) + "," + matcher.group(4) : "";
    }

    // ADICIONADO: comparação permite os dois formatos válidos do mesmo display.
    private static boolean sameSize(String first, String second) {
        if (first == null || second == null || first.isEmpty() || second.isEmpty()) return false;
        if (first.equals(second)) return true;
        int[] a = parseSize(first);
        int[] b = parseSize(second);
        return a[0] == b[1] && a[1] == b[0];
    }

    // CORRIGIDO ALONGAR TELA: não existe restauração pelo caminho legado.
    private static boolean restoreOverscanIfSaved(SharedPreferences prefs) {
        return true;
    }
}
