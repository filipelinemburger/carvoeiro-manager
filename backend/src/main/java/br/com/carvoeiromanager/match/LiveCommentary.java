package br.com.carvoeiromanager.match;

/** Deterministic PT-BR commentary. Variant index derives from the event so replay is stable. */
public final class LiveCommentary {

    private LiveCommentary() {
    }

    private static final String COMEBACK_GOAL_LINE = "Homens de pouca fé... homens de muita fé!";
    private static final String HYPE_GOAL_LINE =
            "Rapaziada eu vou dizer uma coisa pra vocês, esse Criciúma já ta me cheirando a libertadores... Creeedo coisa lindaaa!!";
    private static final String LOSING_STREAK_HALF_TIME_LINE =
            "E não adianta me ligar que eu vou mandar a merda.";

    private static final String[] GOAL = {
            "GOOOOOOOOOOOOOOOOOOL! %s balança as redes!",
            "%s marca! É GOOOOOOOOOOOOOOOOOOL!!! E que golaaaaçoooo!",
            "%s aparece na área e não perdoa! É GOOOOOOOOOOOOOOOOOOL!!!",
            "GOOOOL! %s! Eu tô emocionado, gente, que golaço impressionante!",
            "É GOOOOOOOOOOOOOOOOOOL!!! Bateu, tremeu a rede e caiu o estádio: golaço de %s!",
            "Joga a luva, goleirão! %s manda uma patada e é gooooooooooool!"};
    private static final String[] GOAL_ASSISTED = {
            "%s cruza na medida, %s aparece... É GOOOOOOOOOOOOOOOOOOL!!!",
            "Belo passe de %s, %s só teve o trabalho de empurrar pra dentro! GOOOOOOOOOOOOOOOOOOL!",
            "%s enxergou o espaço, tocou pra %s, e é GOOOOOOOOOOOOOOOOOOL!!! Que jogada coletiva!",
            "Assistência de %s! %s bateu de primeira e mandou pro fundo do gol! GOOOOOOOOOOOOL!"};
    private static final String[] SET_PIECE = {
            "De bola parada... É GOOOOOOOOOOOOOOOOOOL!!! %s converte!", "%s marca na cobrança!"};
    private static final String[] CHANCE = {
            "%s arrisca e a bola passa perto!",
            "Defesaaaaaaçaaaa! %s quase marcou.",
            "%s finaliza, mas para fora!",
            "QUAAAAAASEEEEEE! %s bateu de primeira e a bola raspou a trave! Isso seria uma pintuuura",
            "Olha o lanceee... Miiiinhaaa nossa senhooooraaaa! %s levou a torcida ao desespero nessa finalização! A bola foi lá no colegião"};
    private static final String[] YELLOW = {
            "O juiz não pensou duas vezes: é amareeeelo pra %s!",
            "%s entra forte e o juiz não perdoa: cartão amarelo!",
            "Cartão amarelo pra %s. Cuidado, hein, que com mais um ele desfalca o time!",
            "%s exagerou na entrada e o cartão veio na hora! Se liga aí, moleque!"};
    private static final String[] RED = {
            "É VERMELHOOOO! %s está expulso e vai direto pro vestiário!",
            "%s recebe o vermelho e deixa o time com um a menos. Agora vai ter que suar a camisa!",
            "Vermelho direto! %s vai pro chuveiro mais cedo, e olha que sufoco pro time que fica reduzido!",
            "Não teve jeito, %s: o juiz mostrou o vermelho e a arquibancada foi à loucura!"};
    private static final String[] SECOND_YELLOW_RED = {
            "Segundo amarelo pra %s e consequentemente o VERMELHO! Tá expulso, vai mais cedo pro chuveiro!",
            "%s já tinha amarelo e fez outra! Segundo cartão amarelo, vermelho na sequência: RUA!",
            "Não podia fazer essa falta, %s! O juiz mostra o segundo amarelo e depois o vermelho: EXPULSO!",
            "Bobeira de %s! Segundo amarelo no jogo, o vermelho vem junto e o time fica com um a menos!"};
    private static final String[] INJURY = {
            "%s cai lesionado no gramado, a maca entra e não vai dar pra ele continuar.",
            "%s sente e precisa de atendimento. Pessoal no banco de reservas se agita e demonstra preocupação",
            "Fica no chão %s, e o coração da torcida aperta junto.",
            "É sinal de alerta, %s pede a saída, e o departamento médico já entra em campo."};
    private static final String[] PENALTY_GOAL_LINES = {
            "Pênalti! %s converte com categoria!",
            "É PÊÊÊNAAALTII!!! %s bate... É GOOOOOOOOOOOLLLL!",
            "É GOOOOOOOOOOOLLLL! Que cobrança maravilhooosaaa! Bateu forte e com gosto, %s não erra da marca da cal!",
            "Vai pra bola... É GOOOOOOOOOLLLLLL!!! Goleiro pra um lado e bola para o outro, %s converte de pênalti!"};
    private static final String[] PENALTY_MISS_LINES = {
            "É PÊNALTI... %s vai para a marca da cal eeee.... PERDEEEUUU! O que é isso meu filho? Essa aí tu mandou lá no Maracajá!",
            "%s vai pra cobrança do pênalti eeeee... perdeeeeeuuuu! O goleiro foi buscar a bola lá na Seis de Janeiro!",
            "É PÊÊÊNAAALTII!!! Que sufoco... %s bateu em cima do goleiro e perdeu a chance de ouro! Esse aí não serve nem pra jogar o praião",
            "É PÊÊÊNAAALTII!!! É agora, correu pra bola eee.... PERDEEEEEEEUUUU! O que é isso? %s pelo amor de Deus, assim você não merece aumento"};
    private static final String[] FREE_KICK_LINES = {
            "Falta perigosa, %s na bola... bateeeeeeuuuu.... GOLAAAAAAÇOOOOO!!! GOOOOOOOOOOOOLLL! A bola foi por cima da barreira e morreu no fundo do gol!",
            "Mais uma falta perigosa, %s correu... bateeeu... JOOOOGAAA A LUUUVA GOLEIRÃÃÃOOOO!!! GOOOOOOOOOOOLLLLLLL!!! Isso não foi uma falta, mas sim uma obra de arte!",
            "Pra ele ali é meio gol... bateeeeeuu... ÉÉÉÉÉÉÉÉÉÉ GOLAAAAÇOOOOOOOOO! %s acerta o ângulo e vai pra torcidaa! Cuidado pra não tomar cartão amarelo!",
            "Falta cobrada por %s... É GOOOOOOOOOOOOOOL! JOGA A LUUUUUVA GOLEIRÃOOOOOO"};
    private static final String[] CORNER_TWO_NAME = {
            "%s levanta na área, e %s sobe mais alto que todos para cabecear: É GOOOOOL!",
            "Escanteio de %s, %s sobe e cabeceia: GOL!",
            "Cobrança de %s, e %s aparece de cabeça pra estufar a rede!",
            "%s bate o escanteio, %s sobe sozinho e manda pra dentro! Que jogada de bola parada!"};
    private static final String[] CORNER_ONE_NAME = {
            "Subiu mais que todo mundo! %s cabeceia e é GOOOOOL no escanteio!",
            "GOL de escanteio! %s aparece na área e não perdoa!",
            "Bola levantada na área, %s testa e balança as redes!",
            "Escanteio, cabeçada e GOL! %s não perdoa dentro da pequena área!"};
    private static final String[] SUBSTITUTION_LINES = {
            "Substituição! Entra %s, sai %s. Vamo que vamo!",
            "Troca no time: entra %s no lugar de %s. Fôlego novo pra reta final!",
            "Mudança do treinador: %s entra, %s sai de campo aplaudido, bem merecidamente!",
            "Aí vem a mexida! %s pisa no gramado no lugar de %s."};
    private static final String[] HALF_TIME_LINES = {
            "Apita o juiz e o primeiro tempo chega ao fim! Bora rever o que deu e o que não deu",
            "Intervalo — fim do primeiro tempo. Hora de ajustar as peças, professor!",
            "Hora do cafezinho, torcida! Os times vão pro vestiário e a gente já volta com mais",
            "Fim de primeiro tempo, e olha que jogo emocionante estamos tendo hoje!"};
    private static final String[] TACTICS_USER_LINES = {
            "O treinador mexe no time, vamos ver o que muda em campo!",
            "Mudança tática do treinador. Vamo ver se essa mudança pega bem",
            "Ajuste na estratégia — o comandante não fica parado no banco, hein!"};
    private static final String[] TACTICS_PUSH = {
            "Eles sobem as linhas e vão pra cima. Segura aí, professor!",
            "O adversário se lança ao ataque. Agora esse relógio tem que passar voando",
            "Postura ofensiva do adversário, que quer pressionar lá na frente!"};
    private static final String[] TACTICS_ALL_OUT = {
            "É tudo ou nada agora! O adversário joga por tudo! Essa é hora de apostar no nosso contra-ataque!",
            "Tudo ou nada do adversário: 4-2-4 ofensivo!",
            "Sem medo de perder: o rival lança todo mundo no ataque! Te segura aí, Tigre!"};
    private static final String[] TACTICS_PROTECT = {
            "O rival se fecha e tenta administrar a vantagem, temos que achar um jeito de penetrar nessa retranca e marcar um gol logo",
            "O adversário recua para segurar o resultado. Hora de avançarmos as linhas e pressioná-los no campo de defesa",
            "Eles priorizam a marcação para não sofrer o empate, mas nós vamos com tudo!"};
    private static final String[] TACTICS_PARK_THE_BUS = {
            "Ônibus estacionado! Eles agora só pensam em defender! Tem que furar essa barreira",
            "Adversário fecha-se na defesa: 5-4-1. Tem que encurralar os caras, vamo pra cima!",
            "Ninguém passa! O rival montou uma muralha lá atrás! Bem coisa de time pequeno!"};
    private static final String[] TACTICS_NORMAL = {
            "O comandante rival ajusta as peças no tabuleiro!",
            "O adversário reorganiza a equipe. Postura mais equilibrada agora",
            "Reorganização no adversário, buscando equilíbrio dentro de campo."};

    // Sad PT-BR mirrors of the goal-family pools above, used when the scoring side is the
    // opponent rather than the managed club. Lines take the scorer (%s) and, where the wording
    // calls for it, the opponent club name (second %s) — see pickOpp/pickOpp3.
    private static final String[] OPP_GOAL = {
            "Gol deles... a zaga bate cabeça e o %s aproveita.",
            "%s marca para o %s e o estádio fica em silêncio.",
            "Vazou atrás! %s aparece livre e balança as redes contra nós.",
            "Golpe duro... %s marca pelo %s e a torcida baixa a cabeça.",
            "Que baque, torcida... %s não perdoa e é gol do adversário.",
            "Doeu... %s decreta o gol do %s e o silêncio toma conta da arquibancada."};
    private static final String[] OPP_GOAL_ASSISTED = {
            "Bola alçada na área... %s cruza, %s cabeceia e é gol deles.",
            "Assistência amarga: %s serve %s, que não perdoa contra nós.",
            "%s enxergou o espaço, tocou pra %s do %s, e é gol do adversário.",
            "Jogada coletiva deles: %s cruza, %s empurra pro gol do %s."};
    private static final String[] OPP_PENALTY_GOAL = {
            "Pênalti pra eles... %s não desperdiça.",
            "É pênalti a favor do adversário... %s bate e marca. Que sufoco.",
            "Vai pra bola... %s converte o pênalti pelo %s e complica nossa vida.",
            "Doeu... %s cobra com categoria e amplia o placar do %s."};
    private static final String[] OPP_FREE_KICK_GOAL = {
            "Falta perigosa... %s bate e balança as redes contra nós.",
            "Cobrança certeira do adversário: %s acerta o ângulo e marca pelo %s.",
            "A barreira nem pulou... %s manda uma bomba e é gol deles.",
            "Que pontaria... %s cobra a falta e decreta mais um gol do %s."};
    private static final String[] OPP_CORNER_GOAL = {
            "Escanteio cobrado, %s sobe mais alto e cabeceia pra dentro. Gol deles.",
            "Bola na área, %s testa e amplia o placar do %s.",
            "Subiu sozinho... %s cabeceia e balança as redes contra nós.",
            "Que falha na marcação... %s aproveita o escanteio e marca pelo %s."};

    /**
     * {@code inName} is used for substitutions, corner goals (the corner taker), and assisted
     * open-play goals (the assister — {@code playerInId} on the event). {@code detail} only for
     * TACTICS_CHANGE (AI posture name) and for a RED_CARD produced by a second yellow
     * ({@code LiveTimelineEvent.SECOND_YELLOW_DETAIL} — picks the dedicated pool).
     * {@code comebackGoal} true only for a goal-family type that erases a deficit
     * for the managed club — appends the signature line after the normal goal description, so
     * the scorer and the goal itself are still described. {@code hypeGoal} true only for a
     * goal-family type that is the first in the match to either arrive with the managed club
     * already on a hot streak entering the match, or push the managed club's in-match lead to 3+
     * goals — appends its own signature line the same way, independently of (and combinable
     * with) {@code comebackGoal}. {@code losingStreakHalfTime} true only at HALF_TIME when the
     * managed club has lost each of its last 2 completed fixtures — overrides the normal
     * half-time pool with the signature line (half-time has no per-event scorer/action to
     * preserve, unlike a goal).
     */
    public static String of(EventType type, String playerName, String inName, int minute, String detail,
                             boolean comebackGoal, boolean hypeGoal, boolean losingStreakHalfTime) {
        return of(type, playerName, inName, minute, detail, comebackGoal, hypeGoal, losingStreakHalfTime,
                false, null);
    }

    /**
     * Overload adding {@code opponentGoal}/{@code opponentClubName}: when {@code opponentGoal} is
     * true and {@code type} is a goal-family type with a dedicated sad pool (GOAL, PENALTY_GOAL,
     * FREE_KICK_GOAL, CORNER_GOAL), the line comes from the corresponding OPP_* pool instead,
     * naming the scorer and — where the line has a second {@code %s} — {@code opponentClubName}.
     * {@code comebackGoal}/{@code hypeGoal} are expected false for opponent goals (the caller
     * gates both on the managed side), but this overload does not re-enforce that — it simply
     * appends whatever the caller passes, same as the original signature. SET_PIECE_GOAL is
     * legacy (no longer emitted) and has no opponent pool: it always uses the neutral SET_PIECE
     * pool regardless of {@code opponentGoal}.
     */
    public static String of(EventType type, String playerName, String inName, int minute, String detail,
                             boolean comebackGoal, boolean hypeGoal, boolean losingStreakHalfTime,
                             boolean opponentGoal, String opponentClubName) {
        if (type == EventType.HALF_TIME && losingStreakHalfTime) {
            return LOSING_STREAK_HALF_TIME_LINE;
        }
        String base = switch (type) {
            case GOAL -> opponentGoal
                    ? (inName != null
                            ? pickOpp3(OPP_GOAL_ASSISTED, inName, playerName, opponentClubName, minute, type)
                            : pickOpp(OPP_GOAL, playerName, opponentClubName, minute, type))
                    : (inName != null
                            ? pick2(GOAL_ASSISTED, inName, playerName, minute, type)
                            : pick(GOAL, playerName, minute, type));
            case SET_PIECE_GOAL -> pick(SET_PIECE, playerName, minute, type);
            case CHANCE -> pick(CHANCE, playerName, minute, type);
            case YELLOW_CARD -> pick(YELLOW, playerName, minute, type);
            case RED_CARD -> LiveTimelineEvent.SECOND_YELLOW_DETAIL.equals(detail)
                    ? pick(SECOND_YELLOW_RED, playerName, minute, type)
                    : pick(RED, playerName, minute, type);
            case INJURY -> pick(INJURY, playerName, minute, type);
            case SUBSTITUTION -> pick2(SUBSTITUTION_LINES, inName, playerName, minute, type);
            case TACTICS_CHANGE -> tacticsChange(detail, minute);
            case HALF_TIME -> pick0(HALF_TIME_LINES, minute, type);
            case PENALTY_GOAL -> opponentGoal
                    ? pickOpp(OPP_PENALTY_GOAL, playerName, opponentClubName, minute, type)
                    : pick(PENALTY_GOAL_LINES, playerName, minute, type);
            case PENALTY_MISSED -> pick(PENALTY_MISS_LINES, playerName, minute, type);
            case FREE_KICK_GOAL -> opponentGoal
                    ? pickOpp(OPP_FREE_KICK_GOAL, playerName, opponentClubName, minute, type)
                    : pick(FREE_KICK_LINES, playerName, minute, type);
            case CORNER_GOAL -> opponentGoal
                    ? pickOpp(OPP_CORNER_GOAL, playerName, opponentClubName, minute, type)
                    : corner(playerName, inName, minute);
            case FOUL -> throw new IllegalStateException(
                    "FOUL is never rendered — LiveMatchService.toState() must filter it before calling of()");
            case EXTRA_TIME_START -> throw new IllegalStateException(
                    "EXTRA_TIME_START is a match_events-only marker, never emitted into the live timeline");
        };
        if (!type.isGoal()) {
            return base;
        }
        String result = base;
        if (comebackGoal) {
            result += " " + COMEBACK_GOAL_LINE;
        }
        if (hypeGoal) {
            result += " " + HYPE_GOAL_LINE;
        }
        return result;
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

    /**
     * Same deterministic index as {@link #pick}/{@link #pick2}, but always formats with
     * (scorer, opponentClubName): lines with a single {@code %s} simply ignore the unused second
     * argument (String.format only throws on too few args, never on extra ones).
     */
    private static String pickOpp(String[] lines, String scorer, String opponentClubName, int minute,
                                   EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)].formatted(scorer, opponentClubName);
    }

    /** Three-argument variant of {@link #pickOpp} for OPP_GOAL_ASSISTED (assister, scorer, club). */
    private static String pickOpp3(String[] lines, String assister, String scorer, String opponentClubName,
                                    int minute, EventType type) {
        return lines[Math.floorMod(minute + type.ordinal(), lines.length)]
                .formatted(assister, scorer, opponentClubName);
    }
}
