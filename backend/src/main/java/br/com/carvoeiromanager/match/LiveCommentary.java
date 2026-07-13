package br.com.carvoeiromanager.match;

/** Deterministic PT-BR commentary. Variant index derives from the event so replay is stable. */
public final class LiveCommentary {

    private LiveCommentary() {
    }

    private static final String COMEBACK_GOAL_LINE = "Homens de pouca fé... homens de muita fé!";
    private static final String LOSING_STREAK_HALF_TIME_LINE =
            "E não adianta me ligar que eu vou mandar a merda.";

    private static final String[] GOAL = {
            "GOL! %s balança as redes!",
            "%s marca! Que golaço!",
            "%s aparece na área e não perdoa!",
            "GOOOOL! %s! Eu tô emocionado, gente, que golaço impressionante!",
            "Bateu, tremeu a rede e caiu o estádio: golaço de %s!",
            "Não teve chance pro goleiro! %s manda uma bomba e é gol!"};
    private static final String[] SET_PIECE = {
            "GOL de bola parada! %s converte!", "%s marca na cobrança!"};
    private static final String[] CHANCE = {
            "%s arrisca e a bola passa perto!",
            "Grande defesa! %s quase marcou.",
            "%s finaliza, mas para fora!",
            "Ai, quase! %s bateu de primeira e a bola raspou a trave!",
            "Que perigo! %s levou a torcida ao desespero nessa finalização!"};
    private static final String[] YELLOW = {
            "O juiz não pensou duas vezes: amarelo pra %s!",
            "%s entra forte e é advertido.",
            "Cartão amarelo para %s.",
            "%s exagerou na entrada e o cartão veio na hora!"};
    private static final String[] RED = {
            "Cartão vermelho! %s está expulso!",
            "%s recebe o vermelho e deixa o time com um a menos.",
            "Vermelho direto! %s vai pro chuveiro mais cedo!",
            "Não teve jeito, %s: o juiz mostrou o vermelho e a arquibancada foi à loucura!"};
    private static final String[] INJURY = {
            "%s cai lesionado no gramado.",
            "Preocupação: %s sente e precisa de atendimento.",
            "Fica no chão %s, e o coração da torcida aperta junto.",
            "Sinal de alerta: %s pede a saída, e o departamento médico já entra em campo."};
    private static final String[] PENALTY_GOAL_LINES = {
            "Pênalti! %s converte com categoria!",
            "GOL DE PÊNALTI! %s não desperdiça!",
            "Bateu forte, bateu com gosto: %s não erra da marca da cal!",
            "Frio na barriga e categoria: %s manda pro canto e é gol de pênalti!"};
    private static final String[] PENALTY_MISS_LINES = {
            "Pênalti perdido! %s desperdiça — defesa do goleiro!",
            "%s cobra o pênalti... e o goleiro defende!",
            "Ai, que sufoco! %s bateu em cima do goleiro e perdeu a chance de ouro!",
            "Não foi dessa vez: %s manda para fora e a torcida leva as mãos à cabeça!"};
    private static final String[] FREE_KICK_LINES = {
            "Que cobrança! %s bateu por cima da barreira e é gol de placa!",
            "Direto pro ângulo! %s pintou a obra de arte na bola parada!",
            "GOL DE FALTA! %s acerta o ângulo!",
            "Falta cobrada por %s... GOL!"};
    private static final String[] CORNER_TWO_NAME = {
            "%s levanta na área, e %s sobe mais alto que todos para cabecear: GOL!",
            "Escanteio de %s, %s sobe e cabeceia: GOL!",
            "Cobrança de %s, e %s aparece de cabeça para estufar a rede!",
            "%s bate o escanteio, %s sobe sozinho e manda pra dentro!"};
    private static final String[] CORNER_ONE_NAME = {
            "Subiu mais que todo mundo! %s cabeceia e é gol no escanteio!",
            "GOL de escanteio! %s aparece na área e marca!",
            "Bola levantada na área, %s testa e balança as redes!",
            "Escanteio, cabeçada e GOL! %s não perdoa dentro da pequena área!"};
    private static final String[] SUBSTITUTION_LINES = {
            "Substituição: entra %s, sai %s.",
            "Troca no time: entra %s no lugar de %s.",
            "Mudança do treinador: %s entra, %s sai de campo aplaudido.",
            "Aí vem a mexida! %s pisa no gramado no lugar de %s."};
    private static final String[] HALF_TIME_LINES = {
            "Apita o juiz e o primeiro tempo chega ao fim!",
            "Intervalo — fim do primeiro tempo.",
            "Hora do cafezinho, torcida! Os times vão pro vestiário.",
            "Fim de primeiro tempo, e olha que jogo emocionante estamos tendo hoje!"};
    private static final String[] TACTICS_USER_LINES = {
            "O treinador mexe no time, vamos ver o que muda em campo!",
            "Mudança tática do treinador.",
            "Ajuste na estratégia — o comandante não fica parado no banco!"};
    private static final String[] TACTICS_PUSH = {
            "Time visitante sobe as linhas e vai pra cima!",
            "O adversário se lança ao ataque.",
            "Postura ofensiva do adversário, que quer pressionar lá na frente!"};
    private static final String[] TACTICS_ALL_OUT = {
            "É tudo ou nada agora! O adversário joga por tudo!",
            "Tudo ou nada do adversário: 4-2-4 ofensivo!",
            "Sem medo de perder: o rival lança todo mundo no ataque!"};
    private static final String[] TACTICS_PROTECT = {
            "O rival se fecha e tenta administrar a vantagem!",
            "O adversário recua para segurar o resultado.",
            "Time visitante prioriza a marcação para não sofrer o empate!"};
    private static final String[] TACTICS_PARK_THE_BUS = {
            "Ônibus estacionado! O adversário só pensa em defender!",
            "Adversário fecha-se na defesa: 5-4-1.",
            "Ninguém passa! O rival montou uma muralha lá atrás!"};
    private static final String[] TACTICS_NORMAL = {
            "O comandante rival ajusta as peças no tabuleiro!",
            "O adversário reorganiza a equipe.",
            "Reorganização no adversário, buscando equilíbrio dentro de campo."};

    /**
     * {@code inName} is only used for substitutions and corner goals (the corner taker).
     * {@code detail} only for TACTICS_CHANGE. {@code comebackGoal} true only for a goal-family
     * type that erases a deficit for the managed club — overrides the normal goal-family pool
     * with the signature line. {@code losingStreakHalfTime} true only at HALF_TIME when the
     * managed club has lost each of its last 2 completed fixtures — overrides the normal
     * half-time pool with the signature line.
     */
    public static String of(EventType type, String playerName, String inName, int minute, String detail,
                             boolean comebackGoal, boolean losingStreakHalfTime) {
        if (comebackGoal && type.isGoal()) {
            return COMEBACK_GOAL_LINE;
        }
        if (type == EventType.HALF_TIME && losingStreakHalfTime) {
            return LOSING_STREAK_HALF_TIME_LINE;
        }
        return switch (type) {
            case GOAL -> pick(GOAL, playerName, minute, type);
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> pick2(SUBSTITUTION_LINES, inName, playerName, minute, type);
            case TACTICS_CHANGE -> tacticsChange(detail, minute);
            case HALF_TIME -> pick0(HALF_TIME_LINES, minute, type);
            case PENALTY_GOAL -> pick(PENALTY_GOAL_LINES, playerName, minute, type);
            case PENALTY_MISSED -> pick(PENALTY_MISS_LINES, playerName, minute, type);
            case FREE_KICK_GOAL -> pick(FREE_KICK_LINES, playerName, minute, type);
            case CORNER_GOAL -> corner(playerName, inName, minute);
        };
    }

    /** Two-name line when the taker is known and distinct; single-name fallback otherwise. */
    private static String corner(String scorerName, String takerName, int minute) {
        if (takerName != null && !takerName.equals(scorerName)) {
            return pick2(CORNER_TWO_NAME, takerName, scorerName, minute, EventType.CORNER_GOAL);
        }
        return pick(CORNER_ONE_NAME, scorerName, minute, EventType.CORNER_GOAL);
    }

    /** Null detail = the user's own change (generic pool); otherwise an AI posture name. */
    private static String tacticsChange(String detail, int minute) {
        if (detail == null) return pick0(TACTICS_USER_LINES, minute, EventType.TACTICS_CHANGE);
        return switch (AiManagerBrain.Posture.valueOf(detail)) {
            case PUSH -> pick0(TACTICS_PUSH, minute, EventType.TACTICS_CHANGE);
            case ALL_OUT -> pick0(TACTICS_ALL_OUT, minute, EventType.TACTICS_CHANGE);
            case PROTECT -> pick0(TACTICS_PROTECT, minute, EventType.TACTICS_CHANGE);
            case PARK_THE_BUS -> pick0(TACTICS_PARK_THE_BUS, minute, EventType.TACTICS_CHANGE);
            case NORMAL -> pick0(TACTICS_NORMAL, minute, EventType.TACTICS_CHANGE);
        };
    }

    private static String pick(String[] lines, String name, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(name);
    }

    private static String pick2(String[] lines, String name1, String name2, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(name1, name2);
    }

    private static String pick0(String[] lines, int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)];
    }
}
