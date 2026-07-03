package br.com.carvoeiromanager.match;

/** Deterministic PT-BR commentary. Variant index derives from the event so replay is stable. */
public final class LiveCommentary {

    private LiveCommentary() {
    }

    private static final String[] GOAL = {
            "GOL! %s balança as redes!", "%s marca! Que golaço!", "%s aparece na área e não perdoa!"};
    private static final String[] SET_PIECE = {
            "GOL de bola parada! %s converte!", "%s marca na cobrança!"};
    private static final String[] CHANCE = {
            "%s arrisca e a bola passa perto!", "Grande defesa! %s quase marcou.",
            "%s finaliza, mas para fora!"};
    private static final String[] YELLOW = {"Cartão amarelo para %s.", "%s entra forte e é advertido."};
    private static final String[] RED = {"Cartão vermelho! %s está expulso!", "%s recebe o vermelho e deixa o time com um a menos."};
    private static final String[] INJURY = {"%s cai lesionado no gramado.", "Preocupação: %s sente e precisa de atendimento."};

    /** {@code inName} is only used for substitutions. */
    public static String of(EventType type, String playerName, String inName, int minute) {
        return switch (type) {
            case GOAL -> pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> "Substituição: entra %s, sai %s.".formatted(inName, playerName);
            case TACTICS_CHANGE -> "Mudança tática do treinador.";
            case HALF_TIME -> "Intervalo — fim do primeiro tempo.";
        };
    }

    private static String pick(String[] lines, String name, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(name);
    }
}
