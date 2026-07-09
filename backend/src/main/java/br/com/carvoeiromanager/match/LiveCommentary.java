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
    private static final String[] PENALTY_GOAL_LINES = {
            "Pênalti! %s converte com categoria!", "GOL DE PÊNALTI! %s não desperdiça!"};
    private static final String[] PENALTY_MISS_LINES = {
            "Pênalti perdido! %s desperdiça — defesa do goleiro!",
            "%s cobra o pênalti... e o goleiro defende!"};
    private static final String[] FREE_KICK_LINES = {
            "GOL DE FALTA! %s acerta o ângulo!", "Falta cobrada por %s... GOL!"};

    /** {@code inName} is only used for substitutions and corner goals (the corner taker). {@code detail} only for TACTICS_CHANGE. */
    public static String of(EventType type, String playerName, String inName, int minute, String detail) {
        return switch (type) {
            case GOAL -> pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> "Substituição: entra %s, sai %s.".formatted(inName, playerName);
            case TACTICS_CHANGE -> tacticsChange(detail);
            case HALF_TIME -> "Intervalo — fim do primeiro tempo.";
            case PENALTY_GOAL -> pick(PENALTY_GOAL_LINES, playerName, minute, type);
            case PENALTY_MISSED -> pick(PENALTY_MISS_LINES, playerName, minute, type);
            case FREE_KICK_GOAL -> pick(FREE_KICK_LINES, playerName, minute, type);
            case CORNER_GOAL -> corner(playerName, inName);
        };
    }

    /** Two-name line when the taker is known and distinct; single-name fallback otherwise. */
    private static String corner(String scorerName, String takerName) {
        if (takerName != null && !takerName.equals(scorerName)) {
            return "Escanteio de %s, %s sobe e cabeceia: GOL!".formatted(takerName, scorerName);
        }
        return "GOL de escanteio! %s aparece na área e marca!".formatted(scorerName);
    }

    /** Null detail = the user's own change (generic text); otherwise an AI posture name. */
    private static String tacticsChange(String detail) {
        if (detail == null) return "Mudança tática do treinador.";
        return switch (AiManagerBrain.Posture.valueOf(detail)) {
            case PUSH -> "O adversário se lança ao ataque.";
            case ALL_OUT -> "Tudo ou nada do adversário: 4-2-4 ofensivo!";
            case PROTECT -> "O adversário recua para segurar o resultado.";
            case PARK_THE_BUS -> "Adversário fecha-se na defesa: 5-4-1.";
            case NORMAL -> "O adversário reorganiza a equipe.";
        };
    }

    private static String pick(String[] lines, String name, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(name);
    }
}
