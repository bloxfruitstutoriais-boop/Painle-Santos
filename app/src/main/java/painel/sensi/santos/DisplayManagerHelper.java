package painel.sensi.santos;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Controle do display pelo caminho oficial de IWindowManager.
 *
 * O método principal é Reflection + IWindowManager. Shizuku é usado somente
 * como fallback para wm size/wm density e settings globais. Não há caminho
 * de política compat de pacote, injeção de input, espelhamento ou transformação de
 * coordenadas neste componente.
 */
public final class DisplayManagerHelper {
    private static final String TAG = "DISPLAY_DEBUG";
    private static final String PREFS_NAME = "santos_display_snapshot";
    private static final String MISSING = "__MISSING__";
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;
    // CORRIGIDO: aceitar qualquer dimensão/densidade positiva; os limites reais
    // vêm do Display.getRealSize()/getRealMetrics() antes de cada escrita.
    private static final int MIN_DIMENSION = 1;
    private static final int MIN_DENSITY = 1;
    private static final int MAX_DENSITY = 1000;
    // Faixa CAT obrigatória; o intervalo legado não é usado.
    private static final float MIN_RATIO = 1.00f;
    private static final float MAX_RATIO = 2.00f;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // CORRIGIDO: conserva a causa real dos dois caminhos de display para que
    // um erro não seja convertido em falso sucesso na interface.
    private static volatile String lastCoreDetail = "";

    public enum Preset { DEFAULT, FULLSCREEN, STRETCHED }

    // CORRIGIDO: estado explícito e lock único para impedir duas operações de
    // display simultâneas durante troca de resolução, DPI ou rollback.
    public enum DisplayOperationState {
        IDLE, APPLYING_SIZE, APPLYING_DENSITY, APPLYING_SETTINGS, VERIFYING, ROLLING_BACK, FINISHED, FAILED
    }
    private static final Object DISPLAY_OPERATION_LOCK = new Object();
    private static volatile DisplayOperationState displayOperationState = DisplayOperationState.IDLE;

    private static boolean beginDisplayOperation(String name) {
        synchronized (DISPLAY_OPERATION_LOCK) {
            if (displayOperationState != DisplayOperationState.IDLE) {
                lastCoreDetail = "operação de display ocupada: estado=" + displayOperationState;
                Log.w(TAG, lastCoreDetail + " · rejeitada=" + name);
                return false;
            }
            displayOperationState = DisplayOperationState.APPLYING_SIZE;
            Log.d(TAG, "DISPLAY_STATE=" + displayOperationState + " operation=" + name);
            return true;
        }
    }

    private static void setDisplayOperationState(DisplayOperationState state) {
        displayOperationState = state;
        Log.d(TAG, "DISPLAY_STATE=" + state);
    }

    private static void finishDisplayOperation(boolean ok) {
        synchronized (DISPLAY_OPERATION_LOCK) {
            setDisplayOperationState(ok ? DisplayOperationState.FINISHED : DisplayOperationState.FAILED);
            // O estado só volta a IDLE depois que a confirmação/rollback terminou.
            setDisplayOperationState(DisplayOperationState.IDLE);
            DISPLAY_OPERATION_LOCK.notifyAll();
        }
    }

    public interface Callback {
        void onFinished(boolean ok, String message);
    }

    private static final class PhysicalDisplay {
        final int realWidth;
        final int realHeight;
        final int portraitWidth;
        final int portraitHeight;
        final int density;
        final int rotation;

        PhysicalDisplay(int realWidth, int realHeight, int density, int rotation) {
            this.realWidth = realWidth;
            this.realHeight = realHeight;
            this.portraitWidth = Math.min(realWidth, realHeight);
            this.portraitHeight = Math.max(realWidth, realHeight);
            this.density = density;
            this.rotation = rotation;
        }

        // CORRIGIDO: IWindowManager.setForcedDisplaySize e `wm size` recebem
        // a base física em orientação natural (largura curta x altura longa).
        // A rotação da tela é responsabilidade do WindowManager; não trocar
        // os lados aqui evita inverter a imagem no modo paisagem.
        int[] oriented(int portraitWidth, int portraitHeight) {
            return new int[]{portraitWidth, portraitHeight};
        }

        int[] physicalOriented() {
            return new int[]{portraitWidth, portraitHeight};
        }

        float physicalRatio() {
            return portraitWidth <= 0 ? 0f : portraitHeight / (float) portraitWidth;
        }
    }

    private static final class RemoteDisplay {
        final String physicalSize;
        final String overrideSize;
        final String physicalDensity;
        final String overrideDensity;
        final int currentWidth;
        final int currentHeight;
        final int currentDensity;

        RemoteDisplay(String physicalSize, String overrideSize, String physicalDensity,
                      String overrideDensity, int currentWidth, int currentHeight,
                      int currentDensity) {
            this.physicalSize = physicalSize;
            this.overrideSize = overrideSize;
            this.physicalDensity = physicalDensity;
            this.overrideDensity = overrideDensity;
            this.currentWidth = currentWidth;
            this.currentHeight = currentHeight;
            this.currentDensity = currentDensity;
        }
    }

    private static final class CompatSnapshot {
        final boolean known;
        final boolean aspect;
        final boolean aspectLarge;
        final boolean forceResize;

        CompatSnapshot(boolean known, boolean aspect, boolean aspectLarge, boolean forceResize) {
            this.known = known;
            this.aspect = aspect;
            this.aspectLarge = aspectLarge;
            this.forceResize = forceResize;
        }
    }

    private static final class Snapshot {
        final int physicalWidth;
        final int physicalHeight;
        final int physicalDensity;
        final int rotation;
        final String originalSize;
        final String originalDensity;
        final boolean sizeOverride;
        final boolean densityOverride;
        final String cutout;
        final String policy;
        final String forceResizable;
        final CompatSnapshot compat;

        Snapshot(int physicalWidth, int physicalHeight, int physicalDensity, int rotation,
                 String originalSize, String originalDensity, boolean sizeOverride,
                 boolean densityOverride, String cutout, String policy,
                 String forceResizable, CompatSnapshot compat) {
            this.physicalWidth = physicalWidth;
            this.physicalHeight = physicalHeight;
            this.physicalDensity = physicalDensity;
            this.rotation = rotation;
            this.originalSize = originalSize;
            this.originalDensity = originalDensity;
            this.sizeOverride = sizeOverride;
            this.densityOverride = densityOverride;
            this.cutout = cutout;
            this.policy = policy;
            this.forceResizable = forceResizable;
            this.compat = compat;
        }
    }

    private static final class ReflectionWindowManager {
        final Object service;
        final Method setSize;
        final Method setDensityForUser;
        final Method setDensityLegacy;
        final Method clearSize;
        final Method clearDensityForUser;
        final Method clearDensityLegacy;
        final int userId;

        ReflectionWindowManager(Object service, Method setSize, Method setDensityForUser,
                                Method setDensityLegacy, Method clearSize,
                                Method clearDensityForUser, Method clearDensityLegacy,
                                int userId) {
            this.service = service;
            this.setSize = setSize;
            this.setDensityForUser = setDensityForUser;
            this.setDensityLegacy = setDensityLegacy;
            this.clearSize = clearSize;
            this.clearDensityForUser = clearDensityForUser;
            this.clearDensityLegacy = clearDensityLegacy;
            this.userId = userId;
        }

        static ReflectionWindowManager connect() {
            try {
                Class<?> serviceManager = Class.forName("android.os.ServiceManager");
                Object binder = serviceManager.getMethod("getService", String.class)
                        .invoke(null, "window");
                if (!(binder instanceof IBinder)) {
                    lastCoreDetail = "Reflection: binder window indisponível";
                    Log.w(TAG, lastCoreDetail);
                    return null;
                }
                Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
                Object service = stub.getMethod("asInterface", IBinder.class)
                        .invoke(null, binder);
                Class<?> iWindowManager = Class.forName("android.view.IWindowManager");
                Method setSize = iWindowManager.getMethod(
                        "setForcedDisplaySize", int.class, int.class, int.class);
                Method clearSize = iWindowManager.getMethod(
                        "clearForcedDisplaySize", int.class);
                // CORRIGIDO: Android 8+ usa as variantes *ForUser; alguns
                // firmwares ainda expõem os nomes antigos. Tentar ambas é o
                // método direto do CAT sem passar por `wm` no caminho principal.
                Method setDensityForUser = findMethod(iWindowManager,
                        "setForcedDisplayDensityForUser", int.class, int.class, int.class);
                Method clearDensityForUser = findMethod(iWindowManager,
                        "clearForcedDisplayDensityForUser", int.class, int.class);
                Method setDensityLegacy = findMethod(iWindowManager,
                        "setForcedDisplayDensity", int.class, int.class);
                Method clearDensityLegacy = findMethod(iWindowManager,
                        "clearForcedDisplayDensity", int.class);
                if (setDensityForUser == null && setDensityLegacy == null) {
                    throw new NoSuchMethodException("setForcedDisplayDensity[ForUser]");
                }
                if (clearDensityForUser == null && clearDensityLegacy == null) {
                    throw new NoSuchMethodException("clearForcedDisplayDensity[ForUser]");
                }
                // CORRIGIDO: a API pública não expõe myUserId em todos os
                // compileSdk; o usuário Android normal do painel é 0.
                int userId = 0;
                Log.i(TAG, "Reflection IWindowManager OK · density="
                        + (setDensityForUser != null ? "ForUser" : "legacy"));
                return new ReflectionWindowManager(service, setSize, setDensityForUser,
                        setDensityLegacy, clearSize, clearDensityForUser,
                        clearDensityLegacy, userId);
            } catch (ClassNotFoundException e) {
                lastCoreDetail = "Reflection: classe não encontrada: " + e.getMessage();
                Log.w(TAG, lastCoreDetail, e);
            } catch (NoSuchMethodException e) {
                lastCoreDetail = "Reflection: método não encontrado: " + e.getMessage();
                Log.w(TAG, lastCoreDetail, e);
            } catch (IllegalAccessException e) {
                lastCoreDetail = "Reflection: acesso recusado: " + e.getMessage();
                Log.w(TAG, lastCoreDetail, e);
            } catch (InvocationTargetException e) {
                lastCoreDetail = "Reflection: InvocationTargetException: " + e.getMessage();
                Log.w(TAG, lastCoreDetail, e);
            } catch (Throwable e) {
                lastCoreDetail = "Reflection: " + e.getClass().getSimpleName() + ": "
                        + safeMessage(e);
                Log.w(TAG, lastCoreDetail, e);
            }
            return null;
        }

        private static Method findMethod(Class<?> type, String name, Class<?>... args) {
            try { return type.getMethod(name, args); }
            catch (Throwable ignored) { return null; }
        }

        boolean setSize(int width, int height) {
            return invoke(setSize, DISPLAY_ID, width, height);
        }

        boolean setDensity(int density) {
            return setDensityForUser != null
                    ? invoke(setDensityForUser, DISPLAY_ID, density, userId)
                    : invoke(setDensityLegacy, DISPLAY_ID, density);
        }

        boolean clearSize() {
            return invoke(clearSize, DISPLAY_ID);
        }

        boolean clearDensity() {
            return clearDensityForUser != null
                    ? invoke(clearDensityForUser, DISPLAY_ID, userId)
                    : invoke(clearDensityLegacy, DISPLAY_ID);
        }

        private boolean invoke(Method method, Object... args) {
            if (method == null) return false;
            try {
                method.invoke(service, args);
                return true;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                lastCoreDetail = "Reflection " + method.getName() + ": "
                        + cause.getClass().getSimpleName() + ": " + safeMessage(cause);
                Log.e(TAG, lastCoreDetail, e);
            } catch (IllegalAccessException | SecurityException e) {
                lastCoreDetail = "Reflection " + method.getName() + ": "
                        + e.getClass().getSimpleName() + ": " + safeMessage(e);
                Log.e(TAG, lastCoreDetail, e);
            } catch (Throwable e) {
                lastCoreDetail = "Reflection " + method.getName() + ": "
                        + e.getClass().getSimpleName() + ": " + safeMessage(e);
                Log.e(TAG, lastCoreDetail, e);
            }
            return false;
        }
    }

    private DisplayManagerHelper() {}

    /** Indica se há snapshot CAT persistido para habilitar a restauração na UI. */
    public static boolean hasSavedSnapshot(Context context) {
        if (context == null) return false;
        try {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean("saved", false);
        } catch (Throwable error) {
            Log.e(TAG, "Falha lendo existência do snapshot", error);
            return false;
        }
    }

    /** Restaura o snapshot salvo sem redefinir silenciosamente para o físico. */
    public static void restoreSavedSnapshot(final Context context, final Callback callback) {
        if (context == null) {
            dispatch(callback, false, "Display: contexto inválido");
            return;
        }
        ShizukuManager.launchIo("santos-display-restore-snapshot", () -> {
            boolean operationOk = false;
            if (!beginDisplayOperation("RESTORE_SNAPSHOT")) {
                dispatch(callback, false, "Display ocupado; aguarde a operação anterior terminar");
                return;
            }
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                if (!prefs.getBoolean("saved", false)) {
                    dispatch(callback, false, "Nenhum snapshot original confirmado foi salvo");
                    return;
                }
                PhysicalDisplay physical = physicalDisplay(context);
                Snapshot snapshot = fromPreferences(prefs, physical);
                boolean ok = rollback(context, snapshot);
                if (!ok) {
                    dispatch(callback, false, "Restauração original não confirmada; snapshot preservado");
                    return;
                }
                clearSnapshot(context);
                operationOk = true;
                dispatch(callback, true, "resolução → densidade → settings → compat restaurados e confirmados");
            } catch (Throwable error) {
                Log.e(TAG, "Falha restaurando snapshot original", error);
                dispatch(callback, false, "Restauração: " + error.getClass().getSimpleName()
                        + " · " + safeMessage(error));
            } finally {
                finishDisplayOperation(operationOk);
            }
        });
    }

    /** Tela cheia preserva o alvo escolhido e só aplica políticas confirmáveis. */
    public static void applyFullscreen(final Context context, final int width, final int height,
                                       final int density, final String gamePackage,
                                       final Callback callback) {
        if (context == null) {
            dispatch(callback, false, "Tela cheia: contexto inválido");
            return;
        }
        ShizukuManager.launchIo("santos-display-fullscreen", () -> {
            Snapshot snapshot = null;
            boolean operationOk = false;
            if (!beginDisplayOperation("FULLSCREEN")) {
                dispatch(callback, false, "Display ocupado; aguarde a operação anterior terminar");
                return;
            }
            try {
                PhysicalDisplay physical = physicalDisplay(context);
                if (!validTarget(physical, width, height) || density < MIN_DENSITY || density > MAX_DENSITY) {
                    dispatch(callback, false, "Tela cheia rejeitada: alvo maior que o display físico ou DPI inválido");
                    return;
                }
                loadOrSaveSnapshot(context, physical);
                snapshot = readCurrentSnapshot(context, physical);
                int[] currentSize = snapshot.originalSize.equals("reset")
                        ? physical.physicalOriented() : parseSize(snapshot.originalSize);
                int currentDensity = snapshot.originalDensity.equals("reset")
                        ? physical.density : parseInt(snapshot.originalDensity);
                boolean coreOk = sameSize(currentSize[0], currentSize[1], width, height)
                        && currentDensity == density;
                if (!coreOk && !applyCore(context, width, height, density)) {
                    failWithRollback(context, snapshot,
                            "Tela cheia: resolução/DPI selecionados não confirmados", callback);
                    return;
                }
                String policy = "immersive.full=" + (isExternalPackage(context, gamePackage) ? gamePackage : "*");
                setDisplayOperationState(DisplayOperationState.APPLYING_SETTINGS);
                if (!putGlobalInt(context, "display_cutout_mode", 1)
                        || !putGlobalString(context, "policy_control", policy)
                        || !putGlobalInt(context, "force_resizable_activities", 1)) {
                    failWithRollback(context, snapshot, "Tela cheia: settings não confirmados", callback);
                    return;
                }
                android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String compatDetail = "compat externo: não solicitado";
                boolean compatConfirmed = true;
                if (isExternalPackage(context, gamePackage)) {
                    Boolean originalCompat = readCompatEnabled(gamePackage);
                    if (originalCompat != null) {
                        prefs.edit().putString("fullscreen_compat_package", gamePackage)
                                .putBoolean("fullscreen_compat_original", originalCompat)
                                .putBoolean("fullscreen_compat_known", true).apply();
                        compatDetail = enableCompatForPackage(gamePackage);
                        compatConfirmed = compatDetail.contains("confirmado");
                    } else {
                        compatDetail = "compat externo: readback indisponível; não aplicado";
                        compatConfirmed = false;
                    }
                }
                boolean confirmed = compatConfirmed && verifyCore(context, width, height, density)
                        && globalEquals(context, "display_cutout_mode", "1")
                        && globalEquals(context, "policy_control", policy)
                        && globalEquals(context, "force_resizable_activities", "1");
                if (!confirmed) {
                    failWithRollback(context, snapshot, "Tela cheia: readback final falhou · " + compatDetail, callback);
                    return;
                }
                operationOk = true;
                dispatch(callback, true, "Tela cheia confirmada sem resetar " + width + "x" + height
                        + " @ " + density + " dpi · cutout=1 · immersive=" + policy
                        + " · " + compatDetail);
            } catch (Throwable error) {
                Log.e(TAG, "Falha na Tela cheia", error);
                failWithRollback(context, snapshot, "Tela cheia: " + error.getClass().getSimpleName()
                        + " · " + safeMessage(error), callback);
            } finally {
                finishDisplayOperation(operationOk);
            }
        });
    }

    /** Limite superior público do slider CAT (sempre 2,00x). */
    public static float maxSupportedRatio(Context context) {
        try {
            return Math.min(MAX_RATIO, Math.max(MIN_RATIO, physicalDisplay(context).physicalRatio()));
        } catch (Throwable error) {
            Log.e(TAG, "Falha lendo razão física", error);
            return MAX_RATIO;
        }
    }

    /**
     * Aplica PADRÃO, TELA CHEIA ou ESTICADO em uma fila Dispatchers.IO.
     * Todas as mudanças falhas executam rollback do snapshot salvo.
     */
    public static void applyStretchPreset(final Context context, final Preset preset,
                                          final float requestedRatio, final Callback callback) {
        if (context == null || preset == null) {
            dispatch(callback, false, "Display: contexto ou preset inválido");
            return;
        }
        ShizukuManager.launchIo("santos-display-" + preset.name(), () -> {
            Snapshot snapshot = null;
            boolean operationOk = false;
            if (!beginDisplayOperation("PRESET_" + preset.name())) {
                dispatch(callback, false, "Display ocupado; aguarde a operação anterior terminar");
                return;
            }
            try {
                if (!hasSecureSettings(context) && !ShizukuBridge.hasPermission()) {
                    dispatch(callback, false, "Reflection requer WRITE_SECURE_SETTINGS; fallback Shizuku indisponível. "
                            + "Conceda via ADB: pm grant painel.sensi.santos "
                            + "android.permission.WRITE_SECURE_SETTINGS · "
                            + ShizukuBridge.status());
                    return;
                }
                PhysicalDisplay physical = physicalDisplay(context);
                if (physical.portraitWidth < MIN_DIMENSION
                        || physical.portraitHeight < MIN_DIMENSION
                        || physical.density < MIN_DENSITY) {
                    dispatch(callback, false, "Display físico inválido ou não suportado");
                    return;
                }
                loadOrSaveSnapshot(context, physical);
                // CORRIGIDO: rollback usa o último estado confirmado lido agora;
                // o preset original persistido continua disponível para restaurar.
                snapshot = readCurrentSnapshot(context, physical);

                if (preset == Preset.DEFAULT) {
                    if (!clearCore(context, physical)) {
                        failWithRollback(context, snapshot,
                                "PADRÃO: resolução/DPI não confirmados · " + lastCoreDetail, callback);
                        return;
                    }
                    setDisplayOperationState(DisplayOperationState.APPLYING_SETTINGS);
                    if (!putGlobalInt(context, "display_cutout_mode", 0)
                            || !deleteGlobal(context, "policy_control")
                            || !deleteGlobal(context, "force_resizable_activities")) {
                        failWithRollback(context, snapshot,
                                "PADRÃO: settings não confirmados", callback);
                        return;
                    }
                    // Não alterar am compat no próprio pacote: essa operação
                    // faz o Android matar/reiniciar o processo do painel em
                    // vários firmwares e era a causa do crash após ~1 s.
                    if (!verifyDefault(context, physical)) {
                        failWithRollback(context, snapshot,
                                "PADRÃO: leitura final não confirmou o estado", callback);
                        return;
                    }
                    clearSnapshot(context);
                    operationOk = true;
                    dispatch(callback, true,
                            "PADRÃO aplicado e confirmado · resolução/DPI físicos · sem offsets legados");
                    return;
                }

                if (preset == Preset.FULLSCREEN) {
                    int[] target = physical.physicalOriented();
                    if (!applyCore(context, target[0], target[1], physical.density)) {
                        failWithRollback(context, snapshot,
                                "TELA CHEIA: resolução/DPI não confirmados · " + lastCoreDetail, callback);
                        return;
                    }
                    setDisplayOperationState(DisplayOperationState.APPLYING_SETTINGS);
                    if (!putGlobalInt(context, "display_cutout_mode", 1)
                            || !putGlobalInt(context, "force_resizable_activities", 1)) {
                        failWithRollback(context, snapshot,
                                "TELA CHEIA: settings não confirmados", callback);
                        return;
                    }
                    if (!verifyCore(context, target[0], target[1], physical.density)
                            || !globalEquals(context, "display_cutout_mode", "1")
                            || !globalEquals(context, "force_resizable_activities", "1")) {
                        failWithRollback(context, snapshot,
                                "TELA CHEIA: confirmação final falhou", callback);
                        return;
                    }
                    operationOk = true;
                    dispatch(callback, true,
                            "TELA CHEIA aplicada e confirmada · resolução física completa");
                    return;
                }

                float ratio = clampRatio(requestedRatio, physical.physicalRatio());
                // Fórmula obrigatória: altura = largura física × razão. Não
                // limitar silenciosamente pela altura física, pois isso mudaria
                // o valor solicitado; a validação do core rejeita alvo impossível.
                int targetHeight = Math.round(physical.portraitWidth * ratio);
                int[] target = physical.oriented(physical.portraitWidth, targetHeight);
                int targetDensity = clampDensity(Math.round(physical.density
                        * targetHeight / (float) physical.portraitHeight));
                Log.d(TAG, "ESTICADO formula L=" + physical.portraitWidth
                        + " H=" + physical.portraitHeight + " ratio=" + formatRatio(ratio)
                        + " target=" + target[0] + "x" + target[1]
                        + " density=" + targetDensity);
                if (!applyCore(context, target[0], target[1], targetDensity)) {
                    failWithRollback(context, snapshot,
                            "ESTICADO: resolução/DPI não confirmados · " + lastCoreDetail, callback);
                    return;
                }
                if (!putGlobalInt(context, "display_cutout_mode", 1)
                        || !putGlobalInt(context, "force_resizable_activities", 1)) {
                    failWithRollback(context, snapshot,
                            "ESTICADO: settings não confirmados", callback);
                    return;
                }
                boolean coreConfirmed = verifyCore(context, target[0], target[1], targetDensity);
                boolean settingsConfirmed = globalEquals(context, "display_cutout_mode", "1")
                        && globalEquals(context, "force_resizable_activities", "1");
                if (!coreConfirmed || !settingsConfirmed) {
                    // O app externo não é alvo desta operação; não executar
                    // am compat no pacote do painel. A tela cheia global usa a
                    // resolução física e as flags globais confirmadas.
                    lastCoreDetail = "releitura final core=" + coreConfirmed
                            + " settings=" + settingsConfirmed;
                    failWithRollback(context, snapshot,
                            "ESTICADO: leitura final não confirmou o estado · " + lastCoreDetail, callback);
                    return;
                }
                operationOk = true;
                dispatch(callback, true, "ESTICADO aplicado e confirmado por releitura · razão "
                        + formatRatio(ratio) + " · " + target[0] + "x" + target[1]
                        + " @ " + targetDensity + " dpi · confirmação visual no aparelho compatível pendente");
            } catch (Throwable error) {
                Log.e(TAG, "Falha no preset; rollback automático", error);
                failWithRollback(context, snapshot,
                        "Display: " + error.getClass().getSimpleName() + " · "
                                + safeMessage(error), callback);
            } finally {
                finishDisplayOperation(operationOk);
            }
        });
    }

    /**
     * CORRIGIDO: o botão original APLICAR resolução / DPI também passa pelo
     * mesmo core CAT. Em modo esticado aplica a fórmula e as políticas; em
     * modo normal aplica o perfil natural sem deixar compat/settings antigos.
     */
    /**
     * CORRIGIDO: preset flagship calcula o DPI dentro do mesmo worker que lê
     * o display físico, evitando aplicar cálculo baseado em UI desatualizada.
     */
    public static void applyFlagshipProfile(final Context context, final int officialHeight,
                                            final int officialWidth, final Callback callback) {
        applyCustomProfile(context, officialWidth, officialHeight, 0, true, callback);
    }

    public static void applyCustomProfile(final Context context, final int width,
                                          final int height, final int density,
                                          final boolean stretched, final Callback callback) {
        if (context == null) {
            dispatch(callback, false, "Display: contexto inválido");
            return;
        }
        ShizukuManager.launchIo("santos-display-custom", () -> {
            Snapshot snapshot = null;
            boolean operationOk = false;
            if (!beginDisplayOperation("CUSTOM_PROFILE")) {
                dispatch(callback, false, "Display ocupado; aguarde a operação anterior terminar");
                return;
            }
            try {
                if (!hasSecureSettings(context) && !ShizukuBridge.hasPermission()) {
                    dispatch(callback, false, "Reflection requer WRITE_SECURE_SETTINGS e fallback Shizuku indisponível");
                    return;
                }
                PhysicalDisplay physical = physicalDisplay(context);
                if (physical.portraitWidth < MIN_DIMENSION || physical.portraitHeight < MIN_DIMENSION
                        || physical.density < MIN_DENSITY) {
                    dispatch(callback, false, "Display físico inválido; nenhuma escrita realizada");
                    return;
                }
                int targetWidth = Math.min(width, height);
                int targetHeight = Math.max(width, height);
                int resolvedDensity = density > 0
                        ? density
                        : clampDensity(Math.round(physical.density
                        * targetHeight / (float) physical.portraitHeight));
                if (targetWidth < MIN_DIMENSION || targetHeight < MIN_DIMENSION
                        || targetWidth > physical.portraitWidth
                        || targetHeight > physical.portraitHeight
                        || resolvedDensity < MIN_DENSITY || resolvedDensity > MAX_DENSITY) {
                    dispatch(callback, false, "Resolução/DPI fora dos limites físicos seguros");
                    return;
                }
                loadOrSaveSnapshot(context, physical);
                // CORRIGIDO: cada tentativa captura o estado efetivo imediatamente
                // antes da escrita, evitando rollback para um snapshot antigo.
                snapshot = readCurrentSnapshot(context, physical);
                if (!applyCore(context, targetWidth, targetHeight, resolvedDensity)) {
                    failWithRollback(context, snapshot,
                            "APLICAR resolução/DPI: core não confirmado · " + lastCoreDetail, callback);
                    return;
                }
                setDisplayOperationState(DisplayOperationState.APPLYING_SETTINGS);
                boolean settingsOk;
                if (stretched) {
                    settingsOk = putGlobalInt(context, "display_cutout_mode", 1)
                            && putGlobalInt(context, "force_resizable_activities", 1);
                } else {
                    settingsOk = putGlobalInt(context, "display_cutout_mode", 0)
                            && deleteGlobal(context, "policy_control")
                            && deleteGlobal(context, "force_resizable_activities");
                }
                if (!settingsOk) {
                    failWithRollback(context, snapshot,
                            "APLICAR resolução/DPI: settings não confirmados", callback);
                    return;
                }
                boolean confirmed = verifyCore(context, targetWidth, targetHeight, resolvedDensity)
                        && globalEquals(context, "display_cutout_mode", stretched ? "1" : "0")
                        && (stretched ? globalEquals(context, "force_resizable_activities", "1")
                        : globalMissing(context, "force_resizable_activities"));
                if (!confirmed) {
                    failWithRollback(context, snapshot,
                            "APLICAR resolução/DPI: releitura final não confirmou o estado", callback);
                    return;
                }
                operationOk = true;
                dispatch(callback, true, (stretched ? "ESTICADO" : "Resolução")
                        + " aplicado e confirmado · " + targetWidth + "x" + targetHeight
                        + " @ " + resolvedDensity + " dpi");
            } catch (Throwable error) {
                Log.e(TAG, "Falha no perfil customizado", error);
                failWithRollback(context, snapshot,
                        "APLICAR resolução/DPI: " + error.getClass().getSimpleName()
                                + " · " + safeMessage(error), callback);
            } finally {
                finishDisplayOperation(operationOk);
            }
        });
    }

    /** Captura explícita para o botão legado de salvar estado. */
    public static void captureSnapshot(final Context context, final Callback callback) {
        if (context == null) {
            dispatch(callback, false, "Display: contexto inválido");
            return;
        }
        ShizukuManager.launchIo("santos-display-snapshot", () -> {
            try {
                if (!hasSecureSettings(context) && !ShizukuBridge.hasPermission()) {
                    dispatch(callback, false, "WRITE_SECURE_SETTINGS não concedida e Shizuku indisponível");
                    return;
                }
                Snapshot snapshot = loadOrSaveSnapshot(context, physicalDisplay(context));
                dispatch(callback, true, "Snapshot original salvo · "
                        + snapshot.originalSize + " · " + snapshot.originalDensity + " dpi");
            } catch (Throwable error) {
                Log.e(TAG, "Falha salvando snapshot", error);
                dispatch(callback, false, "Snapshot: " + safeMessage(error));
            }
        });
    }

    private static PhysicalDisplay physicalDisplay(Context context) {
        try {
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display display = manager == null ? null : manager.getDisplay(DISPLAY_ID);
            if (display == null) {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                display = wm == null ? null : wm.getDefaultDisplay();
            }
            if (display == null) throw new IllegalStateException("Display padrão indisponível");
            Point real = new Point();
            display.getRealSize(real);
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int realWidth = real.x;
            int realHeight = real.y;
            int density = metrics.densityDpi;
            // Depois de um wm size, Display.getRealSize() pode refletir o
            // override lógico em vez do painel físico. Quando Shizuku está
            // autorizado, a linha Physical size/density é a fonte estável para
            // validar o próximo alvo e impede rejeição/crash na segunda tentativa.
            if (ShizukuBridge.hasPermission()) {
                RemoteDisplay remote = readRemoteDisplay();
                int[] physicalSize = remote == null ? new int[]{0, 0}
                        : parseSize(remote.physicalSize);
                int physicalDensity = remote == null ? 0 : parseInt(remote.physicalDensity);
                if (physicalSize[0] > 0 && physicalSize[1] > 0 && physicalDensity > 0) {
                    realWidth = physicalSize[0];
                    realHeight = physicalSize[1];
                    density = physicalDensity;
                    Log.d(TAG, "PHYSICAL_READBACK source=wm physical="
                            + realWidth + "x" + realHeight + " @ " + density);
                }
            }
            return new PhysicalDisplay(realWidth, realHeight, density, display.getRotation());
        } catch (Throwable error) {
            Log.e(TAG, "Falha lendo display físico", error);
            throw new IllegalStateException("Display físico indisponível", error);
        }
    }

    private static boolean hasSecureSettings(Context context) {
        try {
            return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS não concedida", error);
            return false;
        }
    }

    private static int clampDensity(int value) {
        return Math.max(MIN_DENSITY, Math.min(MAX_DENSITY, value));
    }

    private static float clampRatio(float requested, float physicalRatio) {
        // A proporção física só é usada para leitura/diagnóstico. O controle
        // deve continuar oferecendo toda a faixa 1,00x–2,00x; se o alvo for
        // impossível no firmware, a operação falha e faz rollback.
        if (Float.isNaN(requested) || Float.isInfinite(requested)) requested = 1.99f;
        return Math.max(MIN_RATIO, Math.min(MAX_RATIO, requested));
    }

    private static String formatRatio(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String commandDetail(String path, ShizukuBridge.CommandResult result) {
        if (result == null) return path + ": resultado nulo";
        return path + " · exit=" + result.exitCode
                + " · stdout=" + compact(result.stdout)
                + " · stderr=" + compact(result.stderr);
    }

    // CORRIGIDO: CAT Resolution é aplicado em duas etapas observáveis: primeiro
    // tamanho, confirmação e delay; depois DPI, confirmação e releitura final.
    private static boolean applyCore(Context context, int width, int height, int density) {
        lastCoreDetail = "";
        boolean ok = false;
        try {
            PhysicalDisplay physical = physicalDisplay(context);
            if (!validTarget(physical, width, height) || density < MIN_DENSITY
                    || density > MAX_DENSITY) {
                lastCoreDetail = "alvo rejeitado antes da escrita: " + width + "x" + height
                        + " @ " + density + " dpi; físico=" + physical.portraitWidth
                        + "x" + physical.portraitHeight;
                return false;
            }
            setDisplayOperationState(DisplayOperationState.APPLYING_SIZE);
            if (!applySizeOnly(context, width, height) || !verifySize(context, width, height)) {
                lastCoreDetail = lastCoreDetail.isEmpty()
                        ? "Etapa APPLYING_SIZE: resolução não confirmou após atualização do WindowManager"
                        : lastCoreDetail;
                return false;
            }
            if (!verifySize(context, width, height)) {
                lastCoreDetail = "Etapa APPLYING_SIZE: releitura efetiva não confirmou "
                        + width + "x" + height;
                return false;
            }

            setDisplayOperationState(DisplayOperationState.APPLYING_DENSITY);
            if (!applyDensityOnly(context, density) || !verifyDensity(context, density)) {
                lastCoreDetail = lastCoreDetail.isEmpty()
                        ? "Etapa APPLYING_DENSITY: DPI não confirmou após atualização do WindowManager"
                        : lastCoreDetail;
                return false;
            }
            setDisplayOperationState(DisplayOperationState.VERIFYING);
            ok = verifyCore(context, width, height, density);
            if (!ok) {
                lastCoreDetail = "Etapa VERIFYING: resolução/DPI efetivos não confirmaram "
                        + width + "x" + height + " @ " + density + " dpi";
            } else {
                Log.i(TAG, "CORE aplicado em duas etapas e confirmado");
            }
            return ok;
        } catch (Throwable error) {
            lastCoreDetail = "CAT_CORE " + error.getClass().getSimpleName() + ": " + safeMessage(error);
            Log.e(TAG, lastCoreDetail, error);
            return false;
        }
    }

    private static boolean clearCore(Context context, PhysicalDisplay physical) {
        try {
            setDisplayOperationState(DisplayOperationState.APPLYING_SIZE);
            return clearCoreUnlocked(context, physical);
        } catch (Throwable error) {
            lastCoreDetail = "CLEAR_CORE " + error.getClass().getSimpleName() + ": "
                    + safeMessage(error);
            Log.e(TAG, lastCoreDetail, error);
            return false;
        }
    }

    private static boolean clearCoreUnlocked(Context context, PhysicalDisplay physical) {
        lastCoreDetail = "";
        String reflectionDetail = "Reflection não executada";
        ReflectionWindowManager reflection = ReflectionWindowManager.connect();
        if (reflection != null && hasSecureSettings(context)) {
            boolean size = reflection.clearSize();
            boolean dpi = size && reflection.clearDensity();
            int[] target = physical.physicalOriented();
            if (size && dpi && verifyCore(context, target[0], target[1], physical.density)) {
                Log.i(TAG, "CORE restaurado por Reflection IWindowManager");
                return true;
            }
            reflectionDetail = lastCoreDetail.isEmpty()
                    ? "Reflection de restauração não confirmou"
                    : lastCoreDetail;
        } else if (!hasSecureSettings(context)) {
            reflectionDetail = "Reflection bloqueada: WRITE_SECURE_SETTINGS não concedida";
        }
        if (!ShizukuBridge.hasPermission()) {
            lastCoreDetail = reflectionDetail + " · fallback Shizuku: "
                    + ShizukuBridge.status();
            return false;
        }
        ShizukuBridge.CommandResult size = ShizukuBridge.executeArgvForDisplay(
                new String[]{"wm", "size", "reset"});
        if (!size.ok) {
            lastCoreDetail = reflectionDetail + " · fallback "
                    + commandDetail("wm size reset", size);
            return false;
        }
        ShizukuBridge.CommandResult dpi = ShizukuBridge.executeArgvForDisplay(
                new String[]{"wm", "density", "reset"});
        if (!dpi.ok) {
            lastCoreDetail = reflectionDetail + " · fallback "
                    + commandDetail("wm density reset", dpi);
            return false;
        }
        int[] target = physical.physicalOriented();
        if (!verifyCore(context, target[0], target[1], physical.density)) {
            lastCoreDetail = reflectionDetail + " · fallback reset aplicado mas releitura não confirmou";
            return false;
        }
        return true;
    }

    private static boolean verifyDefault(Context context, PhysicalDisplay physical) {
        int[] target = physical.physicalOriented();
        return verifyCore(context, target[0], target[1], physical.density)
                && globalEquals(context, "display_cutout_mode", "0")
                && globalMissing(context, "policy_control")
                && globalMissing(context, "force_resizable_activities");
    }

    private static boolean verifyCore(Context context, int width, int height, int density) {
        for (int i = 0; i < 8; i++) {
            Display current = defaultDisplay(context);
            if (current != null) {
                Point size = new Point();
                DisplayMetrics metrics = new DisplayMetrics();
                try {
                    // CORRIGIDO: getSize/getMetrics medem a área útil e podem
                    // incluir barras; a confirmação precisa reler o display real.
                    current.getRealSize(size);
                    current.getRealMetrics(metrics);
                    if (sameSize(size.x, size.y, width, height)
                            && Math.abs(metrics.densityDpi - density) <= 1) return true;
                } catch (Throwable error) {
                    Log.w(TAG, "Confirmação local falhou", error);
                }
            }
            try { Thread.sleep(150L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (!ShizukuBridge.hasPermission()) return false;
        RemoteDisplay remote = readRemoteDisplay();
        return remote != null && sameSize(remote.currentWidth, remote.currentHeight, width, height)
                && remote.currentDensity == density;
    }

    private static Display defaultDisplay(Context context) {
        try {
            DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            if (manager != null) {
                Display display = manager.getDisplay(DISPLAY_ID);
                if (display != null) return display;
            }
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            return wm == null ? null : wm.getDefaultDisplay();
        } catch (Throwable error) {
            return null;
        }
    }

    private static RemoteDisplay readRemoteDisplay() {
        try {
            ShizukuBridge.CommandResult size = ShizukuBridge.executeArgvForDisplay(
                    new String[]{"wm", "size"});
            ShizukuBridge.CommandResult density = ShizukuBridge.executeArgvForDisplay(
                    new String[]{"wm", "density"});
            if (!size.ok || !density.ok) return null;
            String physicalSize = extract(size.stdout, "Physical size:");
            String overrideSize = extract(size.stdout, "Override size:");
            String physicalDensity = extract(density.stdout, "Physical density:");
            String overrideDensity = extract(density.stdout, "Override density:");
            int[] currentSize = parseSize(overrideSize.isEmpty() ? physicalSize : overrideSize);
            int currentDensity = parseInt(overrideDensity.isEmpty()
                    ? physicalDensity : overrideDensity);
            if (currentSize[0] <= 0 || currentSize[1] <= 0 || currentDensity <= 0) return null;
            return new RemoteDisplay(physicalSize, overrideSize, physicalDensity,
                    overrideDensity, currentSize[0], currentSize[1], currentDensity);
        } catch (Throwable error) {
            Log.e(TAG, "Leitura remota do display falhou", error);
            return null;
        }
    }

    private static RemoteDisplay localRemoteDisplay(Context context, PhysicalDisplay physical) {
        try {
            Display display = defaultDisplay(context);
            if (display == null) return null;
            Point current = new Point();
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealSize(current);
            display.getRealMetrics(metrics);
            int currentWidth = Math.min(current.x, current.y);
            int currentHeight = Math.max(current.x, current.y);
            int currentDensity = metrics.densityDpi;
            if (currentWidth <= 0 || currentHeight <= 0 || currentDensity <= 0) return null;
            String physicalSize = physical.portraitWidth + "x" + physical.portraitHeight;
            String currentSize = currentWidth + "x" + currentHeight;
            String overrideSize = sameSize(currentWidth, currentHeight,
                    physical.portraitWidth, physical.portraitHeight) ? "" : currentSize;
            String physicalDensity = String.valueOf(physical.density);
            String overrideDensity = currentDensity == physical.density
                    ? "" : String.valueOf(currentDensity);
            // CORRIGIDO: reflection pode operar sem Shizuku; o estado atual
            // local ainda é salvo antes da primeira alteração.
            return new RemoteDisplay(physicalSize, overrideSize, physicalDensity,
                    overrideDensity, currentWidth, currentHeight, currentDensity);
        } catch (Throwable error) {
            Log.e(TAG, "Snapshot local do display falhou", error);
            return null;
        }
    }

    private static Snapshot readCurrentSnapshot(Context context, PhysicalDisplay physical) {
        RemoteDisplay remote = readRemoteDisplay();
        if (remote == null) remote = localRemoteDisplay(context, physical);
        if (remote == null || !validTarget(physical, remote.currentWidth, remote.currentHeight)
                || remote.currentDensity < MIN_DENSITY || remote.currentDensity > MAX_DENSITY) {
            throw new IllegalStateException("Readback atual do display é inválido ou excede o físico");
        }
        CompatSnapshot compat = readCompatSnapshot(context);
        String originalSize = remote.overrideSize.isEmpty() ? "reset" : remote.overrideSize;
        String originalDensity = remote.overrideDensity.isEmpty() ? "reset" : remote.overrideDensity;
        return new Snapshot(physical.portraitWidth, physical.portraitHeight,
                physical.density, physical.rotation, originalSize, originalDensity,
                !remote.overrideSize.isEmpty(), !remote.overrideDensity.isEmpty(),
                readGlobal(context, "display_cutout_mode"),
                readGlobalOrMissing(context, "policy_control"),
                readGlobalOrMissing(context, "force_resizable_activities"), compat);
    }

    private static Snapshot loadOrSaveSnapshot(Context context, PhysicalDisplay physical) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.getBoolean("saved", false)) return fromPreferences(prefs, physical);

        RemoteDisplay remote = readRemoteDisplay();
        if (remote == null) remote = localRemoteDisplay(context, physical);
        if (remote == null || !validTarget(physical, remote.currentWidth, remote.currentHeight)
                || remote.currentDensity < MIN_DENSITY || remote.currentDensity > MAX_DENSITY) {
            throw new IllegalStateException("Readback atual do display é inválido ou excede o físico");
        }
        CompatSnapshot compat = readCompatSnapshot(context);
        // Política compat não é escrita nem sondada; o snapshot permanece
        // desconhecido para não matar/reiniciar o pacote do painel.
        String originalSize = remote.overrideSize.isEmpty()
                ? "reset" : remote.overrideSize;
        String originalDensity = remote.overrideDensity.isEmpty()
                ? "reset" : remote.overrideDensity;
        String cutout = readGlobal(context, "display_cutout_mode");
        String policy = readGlobalOrMissing(context, "policy_control");
        String force = readGlobalOrMissing(context, "force_resizable_activities");
        Snapshot snapshot = new Snapshot(physical.portraitWidth, physical.portraitHeight,
                physical.density, physical.rotation, originalSize, originalDensity,
                !remote.overrideSize.isEmpty(), !remote.overrideDensity.isEmpty(), cutout,
                policy, force, compat);
        SharedPreferences.Editor editor = prefs.edit().clear()
                .putBoolean("saved", true)
                .putInt("physical_width", snapshot.physicalWidth)
                .putInt("physical_height", snapshot.physicalHeight)
                .putInt("physical_density", snapshot.physicalDensity)
                .putInt("rotation", snapshot.rotation)
                .putString("original_size", snapshot.originalSize)
                .putString("original_density", snapshot.originalDensity)
                .putBoolean("size_override", snapshot.sizeOverride)
                .putBoolean("density_override", snapshot.densityOverride)
                .putString("cutout", snapshot.cutout)
                .putString("policy", snapshot.policy)
                .putString("force_resizable", snapshot.forceResizable)
                .putBoolean("compat_known", compat.known)
                .putBoolean("compat_aspect", compat.aspect)
                .putBoolean("compat_aspect_large", compat.aspectLarge)
                .putBoolean("compat_force_resize", compat.forceResize);
        if (!editor.commit()) throw new IllegalStateException("Snapshot original não foi salvo");
        Log.d(TAG, "SNAPSHOT original salvo size=" + originalSize + " density=" + originalDensity);
        return snapshot;
    }

    private static Snapshot fromPreferences(SharedPreferences prefs, PhysicalDisplay fallback) {
        return new Snapshot(
                prefs.getInt("physical_width", fallback.portraitWidth),
                prefs.getInt("physical_height", fallback.portraitHeight),
                prefs.getInt("physical_density", fallback.density),
                prefs.getInt("rotation", fallback.rotation),
                prefs.getString("original_size", "reset"),
                prefs.getString("original_density", "reset"),
                prefs.getBoolean("size_override", false),
                prefs.getBoolean("density_override", false),
                prefs.getString("cutout", "0"),
                prefs.getString("policy", MISSING),
                prefs.getString("force_resizable", MISSING),
                new CompatSnapshot(prefs.getBoolean("compat_known", false),
                        prefs.getBoolean("compat_aspect", false),
                        prefs.getBoolean("compat_aspect_large", false),
                        prefs.getBoolean("compat_force_resize", false)));
    }

    private static CompatSnapshot readCompatSnapshot(Context context) {
        // Não sondar am compat durante uma alteração de display. O painel não
        // tem um pacote de jogo escolhido para receber essa política e até a
        // leitura pode ser recusada por alguns firmwares; conservar unknown é
        // mais seguro que iniciar um caminho que mata/reinicia o próprio app.
        return new CompatSnapshot(false, false, false, false);
    }

    private static void failWithRollback(Context context, Snapshot snapshot,
                                         String message, Callback callback) {
        // CORRIGIDO: rollback tem estado próprio e só libera a operação depois
        // da releitura confirmar resolução, DPI, settings e compatibilidade.
        setDisplayOperationState(DisplayOperationState.ROLLING_BACK);
        boolean rollback = snapshot != null && rollback(context, snapshot);
        setDisplayOperationState(DisplayOperationState.FAILED);
        dispatch(callback, false, message + " · rollback=" + (rollback ? "confirmado" : "falhou"));
    }

    private static boolean rollback(Context context, Snapshot snapshot) {
        try {
            PhysicalDisplay physical = physicalDisplay(context);
            boolean sizeOk;
            boolean densityOk;
            if (snapshot.sizeOverride && validSize(snapshot.originalSize)) {
                int[] size = parseSize(snapshot.originalSize);
                sizeOk = applySizeOnly(context, size[0], size[1]);
            } else {
                sizeOk = clearSizeOnly(context, physical);
            }
            if (snapshot.densityOverride && validNumber(snapshot.originalDensity)) {
                densityOk = applyDensityOnly(context, parseInt(snapshot.originalDensity));
            } else {
                densityOk = clearDensityOnly(context, physical);
            }
            boolean settings = restoreGlobal(context, "display_cutout_mode", snapshot.cutout)
                    && restoreGlobal(context, "policy_control", snapshot.policy)
                    && restoreGlobal(context, "force_resizable_activities", snapshot.forceResizable);
            boolean compat = settings && restoreFullscreenCompat(context);
            // Não executar am compat no próprio pacote; o helper acima só aceita
            // package externo explicitamente salvo na UI.
            // Não executar am compat durante rollback: o alvo histórico era o
            // próprio painel e o comando pode matar o processo em andamento.
            // As operações atuais não alteram compat; a leitura permanece apenas
            // diagnóstica.
            boolean core = sizeOk && densityOk;
            if (core && validSize(snapshot.originalSize) && validNumber(snapshot.originalDensity)) {
                int[] size = parseSize(snapshot.originalSize);
                core = verifyCore(context, size[0], size[1], parseInt(snapshot.originalDensity));
            }
            Log.d(TAG, "ROLLBACK core=" + core + " settings=" + settings + " compat=" + compat);
            return core && settings && compat;
        } catch (Throwable error) {
            Log.e(TAG, "Rollback automático falhou", error);
            return false;
        }
    }

    private static boolean applySizeOnly(Context context, int width, int height) {
        try {
            PhysicalDisplay physical = physicalDisplay(context);
            if (!validTarget(physical, width, height)) {
                lastCoreDetail = "resolução rejeitada antes da escrita: " + width + "x" + height;
                return false;
            }
        } catch (Throwable error) {
            lastCoreDetail = "não foi possível validar o display físico: " + safeMessage(error);
            return false;
        }
        ReflectionWindowManager reflection = ReflectionWindowManager.connect();
        if (reflection != null && hasSecureSettings(context)
                && reflection.setSize(width, height)
                && verifySize(context, width, height)) return true;
        if (!ShizukuBridge.hasPermission()) return false;
        ShizukuBridge.CommandResult result = ShizukuBridge.executeArgvForDisplay(
                new String[]{"wm", "size", width + "x" + height});
        boolean readback = result.ok && verifySizeRemote(width, height);
        if (!readback) lastCoreDetail = commandDetail("wm size " + width + "x" + height, result)
                + " · readback=" + readback;
        return readback;
    }

    private static boolean applyDensityOnly(Context context, int density) {
        if (density < MIN_DENSITY || density > MAX_DENSITY) {
            lastCoreDetail = "densidade rejeitada antes da escrita: " + density;
            return false;
        }
        ReflectionWindowManager reflection = ReflectionWindowManager.connect();
        if (reflection != null && hasSecureSettings(context)
                && reflection.setDensity(density) && verifyDensity(context, density)) return true;
        if (!ShizukuBridge.hasPermission()) return false;
        ShizukuBridge.CommandResult result = ShizukuBridge.executeArgvForDisplay(
                new String[]{"wm", "density", String.valueOf(density)});
        boolean readback = result.ok && verifyDensityRemote(density);
        if (!readback) lastCoreDetail = commandDetail("wm density " + density, result)
                + " · readback=" + readback;
        return readback;
    }

    private static boolean clearSizeOnly(Context context, PhysicalDisplay physical) {
        ReflectionWindowManager reflection = ReflectionWindowManager.connect();
        int[] target = physical.physicalOriented();
        if (reflection != null && hasSecureSettings(context)
                && reflection.clearSize() && verifySize(context, target[0], target[1])) return true;
        if (!ShizukuBridge.hasPermission()) return false;
        ShizukuBridge.CommandResult result = ShizukuBridge.executeArgvForDisplay(
                new String[]{"wm", "size", "reset"});
        boolean readback = result.ok && verifySizeRemote(target[0], target[1]);
        if (!readback) lastCoreDetail = commandDetail("wm size reset", result)
                + " · readback=" + readback;
        return readback;
    }

    private static boolean clearDensityOnly(Context context, PhysicalDisplay physical) {
        ReflectionWindowManager reflection = ReflectionWindowManager.connect();
        if (reflection != null && hasSecureSettings(context)
                && reflection.clearDensity() && verifyDensity(context, physical.density)) return true;
        if (!ShizukuBridge.hasPermission()) return false;
        ShizukuBridge.CommandResult result = ShizukuBridge.executeArgvForDisplay(
                new String[]{"wm", "density", "reset"});
        boolean readback = result.ok && verifyDensityRemote(physical.density);
        if (!readback) lastCoreDetail = commandDetail("wm density reset", result)
                + " · readback=" + readback;
        return readback;
    }

    private static boolean verifySize(Context context, int width, int height) {
        for (int i = 0; i < 8; i++) {
            Display display = defaultDisplay(context);
            if (display != null) {
                try {
                    Point size = new Point();
                    // CORRIGIDO: confirmar contra dimensões reais, não contra
                    // a área útil reduzida por barras do sistema.
                    display.getRealSize(size);
                    if (sameSize(size.x, size.y, width, height)) return true;
                } catch (Throwable error) {
                    Log.w(TAG, "Confirmação de tamanho falhou", error);
                }
            }
            sleepForConfirmation();
        }
        return verifySizeRemote(width, height);
    }

    private static boolean verifyDensity(Context context, int density) {
        for (int i = 0; i < 8; i++) {
            Display display = defaultDisplay(context);
            if (display != null) {
                try {
                    DisplayMetrics metrics = new DisplayMetrics();
                    // CORRIGIDO: a densidade efetiva deve ser relida do display real.
                    display.getRealMetrics(metrics);
                    if (Math.abs(metrics.densityDpi - density) <= 1) return true;
                } catch (Throwable error) {
                    Log.w(TAG, "Confirmação de densidade falhou", error);
                }
            }
            sleepForConfirmation();
        }
        return verifyDensityRemote(density);
    }

    private static void sleepForConfirmation() {
        try { Thread.sleep(150L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean verifySizeRemote(int width, int height) {
        RemoteDisplay remote = readRemoteDisplay();
        return remote != null && sameSize(remote.currentWidth, remote.currentHeight, width, height);
    }

    private static boolean verifyDensityRemote(int density) {
        RemoteDisplay remote = readRemoteDisplay();
        return remote != null && remote.currentDensity == density;
    }

    private static boolean sameSize(int firstWidth, int firstHeight,
                                    int secondWidth, int secondHeight) {
        // CORRIGIDO: comparação natural permite o readback rotacionado do
        // Android sem aceitar zero/negativo ou qualquer dimensão fora do alvo.
        if (firstWidth <= 0 || firstHeight <= 0 || secondWidth <= 0 || secondHeight <= 0) {
            return false;
        }
        return (firstWidth == secondWidth && firstHeight == secondHeight)
                || (firstWidth == secondHeight && firstHeight == secondWidth);
    }

    private static boolean validTarget(PhysicalDisplay physical, int width, int height) {
        return physical != null && width >= MIN_DIMENSION && height >= MIN_DIMENSION
                && width <= physical.portraitWidth && height <= physical.portraitHeight;
    }

    private static boolean putGlobalInt(Context context, String name, int value) {
        return putGlobalString(context, name, String.valueOf(value));
    }

    private static boolean putGlobalString(Context context, String name, String value) {
        // CORRIGIDO: quando Shizuku está autorizado, executar a sintaxe CAT
        // literal `settings put global <nome> <valor>` e reler o valor.
        if (ShizukuBridge.hasPermission()) {
            ShizukuBridge.CommandResult shell = ShizukuBridge.executeArgvForDisplay(
                    new String[]{"settings", "put", "global", name, value});
            if (shell.ok && globalEquals(context, name, value)) return true;
        }
        if (!hasSecureSettings(context)) return false;
        try {
            ContentResolver resolver = context.getContentResolver();
            boolean result = Settings.Global.putString(resolver, name, value);
            if (result && globalEquals(context, name, value)) return true;
        } catch (Throwable error) {
            Log.e(TAG, "Settings.Global.put falhou: " + name, error);
        }
        return false;
    }

    private static boolean deleteGlobal(Context context, String name) {
        // CORRIGIDO: a ação shell é preferida; overscan nunca é tentado.
        if (ShizukuBridge.hasPermission()) {
            ShizukuBridge.CommandResult shell = ShizukuBridge.executeArgvForDisplay(
                    new String[]{"settings", "delete", "global", name});
            if (shell.ok && globalMissing(context, name)) return true;
        }
        if (!hasSecureSettings(context)) return false;
        try {
            // CORRIGIDO: ContentResolver.delete(Settings.Global.CONTENT_URI)
            // é rejeitado pelo SettingsProvider; null remove o valor.
            boolean result = Settings.Global.putString(
                    context.getContentResolver(), name, null);
            if (result && globalMissing(context, name)) return true;
        } catch (Throwable error) {
            Log.e(TAG, "Settings.Global.put(null) falhou: " + name, error);
        }
        return false;
    }

    private static boolean restoreGlobal(Context context, String name, String value) {
        return MISSING.equals(value) ? deleteGlobal(context, name)
                : putGlobalString(context, name, value);
    }

    private static String readGlobal(Context context, String name) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(), name);
            return value == null ? "" : value;
        } catch (Throwable error) {
            Log.e(TAG, "Falha lendo global " + name, error);
            return "";
        }
    }

    private static String readGlobalOrMissing(Context context, String name) {
        String value = readGlobal(context, name);
        return value.isEmpty() ? MISSING : value;
    }

    private static boolean globalEquals(Context context, String name, String expected) {
        return expected.equals(readGlobal(context, name));
    }

    private static boolean globalMissing(Context context, String name) {
        return readGlobal(context, name).isEmpty();
    }

    private static String extract(String output, String label) {
        if (output == null) return "";
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(label)) return trimmed.substring(label.length()).trim();
        }
        return "";
    }

    private static int[] parseSize(String value) {
        if (!validSize(value)) return new int[]{0, 0};
        String[] parts = value.split("x", 2);
        return new int[]{parseInt(parts[0]), parseInt(parts[1])};
    }

    private static boolean validSize(String value) {
        return value != null && value.matches("\\d{1,6}x\\d{1,6}");
    }

    private static boolean validExternalPackage(String value) {
        return value != null
                && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    }

    private static boolean isExternalPackage(Context context, String value) {
        return context != null && validExternalPackage(value)
                && !context.getPackageName().equals(value);
    }

    private static Boolean readCompatEnabled(String packageName) {
        if (!validExternalPackage(packageName)
                || "painel.sensi.santos".equals(packageName)
                || !ShizukuBridge.hasPermission()) return null;
        ShizukuBridge.CommandResult result = ShizukuBridge.executeArgvForDisplay(
                new String[]{"am", "compat", "list", packageName});
        if (!result.ok) return null;
        return result.stdout.contains("FORCE_RESIZE_APP");
    }

    private static String enableCompatForPackage(String packageName) {
        if (!validExternalPackage(packageName)
                || "painel.sensi.santos".equals(packageName)) {
            return "compat externo rejeitado: package inválido ou próprio painel";
        }
        if (!ShizukuBridge.hasPermission()) return "compat externo: Shizuku não autorizado; não aplicado";
        ShizukuBridge.CommandResult command = ShizukuBridge.executeArgvForDisplay(
                new String[]{"am", "compat", "enable", "FORCE_RESIZE_APP", packageName});
        Boolean confirmed = readCompatEnabled(packageName);
        return command.ok && Boolean.TRUE.equals(confirmed)
                ? "compat FORCE_RESIZE_APP confirmado para " + packageName
                : "compat externo não confirmado · exit=" + command.exitCode;
    }

    private static boolean restoreFullscreenCompat(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("fullscreen_compat_known", false)) return true;
        String packageName = prefs.getString("fullscreen_compat_package", "");
        if (!isExternalPackage(context, packageName)
                || "painel.sensi.santos".equals(packageName)) return false;
        if (!ShizukuBridge.hasPermission()) return false;
        boolean original = prefs.getBoolean("fullscreen_compat_original", false);
        ShizukuBridge.CommandResult command = ShizukuBridge.executeArgvForDisplay(
                new String[]{"am", "compat", original ? "enable" : "disable", "FORCE_RESIZE_APP", packageName});
        Boolean confirmed = readCompatEnabled(packageName);
        boolean ok = command.ok && confirmed != null && confirmed == original;
        if (ok) prefs.edit().remove("fullscreen_compat_known")
                .remove("fullscreen_compat_package").remove("fullscreen_compat_original").apply();
        return ok;
    }

    private static boolean validNumber(String value) {
        return value != null && value.matches("\\d{1,4}");
    }

    private static int parseInt(String value) {
        try { return Integer.parseInt(value == null ? "" : value); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String compact(String value) {
        if (value == null) return "";
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 240 ? text : text.substring(0, 239) + "…";
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "falha desconhecida" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "falha desconhecida" : compact(message);
    }

    private static void clearSnapshot(Context context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable error) {
            Log.w(TAG, "Falha limpando snapshot", error);
        }
    }

    private static void dispatch(final Callback callback, final boolean ok,
                                 final String message) {
        if (callback == null) return;
        MAIN.post(() -> {
            try {
                callback.onFinished(ok, message);
            } catch (Throwable error) {
                Log.e(TAG, "Callback do display falhou", error);
            }
        });
    }
}
