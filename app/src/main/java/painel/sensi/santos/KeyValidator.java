package painel.sensi.santos;

/**
 * Validação local apenas da entrada da interface.
 * A autorização real é feita exclusivamente por LicenseValidator/API.
 */
public final class KeyValidator {
    private KeyValidator() {}

    public static boolean hasText(String key) {
        return key != null && !key.trim().isEmpty();
    }
}
