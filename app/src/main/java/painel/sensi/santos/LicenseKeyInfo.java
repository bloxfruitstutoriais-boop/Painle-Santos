package painel.sensi.santos; // CORRIGIDO BUG2: cria um modelo explícito para o estado da key e evita misturar validade com acesso a funções.

/** Modelo imutável da licença calculado em UTC. */ // CORRIGIDO BUG2: documenta os dados usados pela decisão de liberação.
public final class LicenseKeyInfo { // CORRIGIDO BUG2: representa a decisão centralizada da licença.
    public final boolean valid; // CORRIGIDO BUG2: informa se existe sessão válida para esta key.
    public final boolean expiryKnown; // CORRIGIDO BUG2: diferencia expiração ausente de key expirada.
    public final boolean expired; // CORRIGIDO BUG2: marca somente uma expiração conhecida com zero dias ou menos.
    public final boolean trial; // CORRIGIDO BUG2: trata data nula/inválida como acesso de trial, sem usar zero como validade.
    public final boolean fullAccess; // CORRIGIDO BUG2: fica true exclusivamente quando diasRestantes >= 7.
    public final Long daysRemaining; // CORRIGIDO BUG2: mantém null para data desconhecida e evita interpretar null como zero.
    public final long expiresAtMillis; // CORRIGIDO BUG2: guarda a data UTC em epoch para recalcular sempre.
    public final long currentAtMillis; // CORRIGIDO BUG2: registra o instante usado no cálculo.
    public final String currentUtc; // CORRIGIDO BUG2: expõe dataAtual em UTC para diagnóstico.
    public final String expirationUtc; // CORRIGIDO BUG2: expõe dataExpiracao em UTC para diagnóstico.
    public final String releaseResult; // CORRIGIDO BUG2: texto da decisão usada pela UI/Logcat.

    public LicenseKeyInfo( // CORRIGIDO BUG2: recebe todos os campos de decisão sem inferências na UI.
            boolean valid, // CORRIGIDO BUG2: preserva a validade da sessão.
            boolean expiryKnown, // CORRIGIDO BUG2: informa se o servidor entregou uma data utilizável.
            boolean expired, // CORRIGIDO BUG2: informa expiração real.
            boolean trial, // CORRIGIDO BUG2: informa fallback seguro para data ausente.
            boolean fullAccess, // CORRIGIDO BUG2: informa liberação total a partir de sete dias.
            Long daysRemaining, // CORRIGIDO BUG2: dias restantes calculados, não um valor de servidor obsoleto.
            long expiresAtMillis, // CORRIGIDO BUG2: expiração em epoch UTC.
            long currentAtMillis, // CORRIGIDO BUG2: instante de cálculo em epoch UTC.
            String currentUtc, // CORRIGIDO BUG2: representação legível da data atual.
            String expirationUtc, // CORRIGIDO BUG2: representação legível da expiração.
            String releaseResult) { // CORRIGIDO BUG2: descrição auditável do resultado.
        this.valid = valid; // CORRIGIDO BUG2: inicializa o estado de sessão.
        this.expiryKnown = expiryKnown; // CORRIGIDO BUG2: inicializa o indicador de data.
        this.expired = expired; // CORRIGIDO BUG2: inicializa a expiração.
        this.trial = trial; // CORRIGIDO BUG2: inicializa o fallback trial.
        this.fullAccess = fullAccess; // CORRIGIDO BUG2: inicializa a liberação total.
        this.daysRemaining = daysRemaining; // CORRIGIDO BUG2: mantém null quando não há data.
        this.expiresAtMillis = expiresAtMillis; // CORRIGIDO BUG2: salva a expiração UTC.
        this.currentAtMillis = currentAtMillis; // CORRIGIDO BUG2: salva o instante de cálculo.
        this.currentUtc = currentUtc == null ? "indisponível" : currentUtc; // CORRIGIDO BUG2: evita texto nulo na UI.
        this.expirationUtc = expirationUtc == null ? "não informada" : expirationUtc; // CORRIGIDO BUG2: evita texto nulo na UI.
        this.releaseResult = releaseResult == null ? "não calculado" : releaseResult; // CORRIGIDO BUG2: evita resultado nulo no diagnóstico.
    }

    public static LicenseKeyInfo empty(long nowMillis) { // CORRIGIDO BUG2: cria estado seguro sem sessão/cache.
        return new LicenseKeyInfo(false, false, false, true, false, null, 0L, nowMillis, // CORRIGIDO BUG2: sessão ausente nunca libera recursos restritos.
                "não calculada", "não informada", "key ausente"); // CORRIGIDO BUG2: mensagem explícita para a UI.
    }
}
