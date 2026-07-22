package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class LiveCommentaryTest {

    // Maps every private String[] pool field in LiveCommentary to the number of arguments its
    // call site passes via .formatted(...). Lines may use fewer placeholders than that (extra
    // args are silently ignored by String.format) but never more (that throws
    // MissingFormatArgumentException) — see everyPoolLinesPlaceholderCountNeverExceedsItsCallArity.
    private static final Map<String, Integer> ARITY_BY_POOL_FIELD = Map.ofEntries(
            Map.entry("GOAL", 1),
            Map.entry("GOAL_ASSISTED", 2),
            Map.entry("SET_PIECE", 1),
            Map.entry("CHANCE", 1),
            Map.entry("YELLOW", 1),
            Map.entry("RED", 1),
            Map.entry("SECOND_YELLOW_RED", 1),
            Map.entry("INJURY", 1),
            Map.entry("PENALTY_GOAL_LINES", 1),
            Map.entry("PENALTY_MISS_LINES", 1),
            Map.entry("FREE_KICK_LINES", 1),
            Map.entry("CORNER_TWO_NAME", 2),
            Map.entry("CORNER_ONE_NAME", 1),
            Map.entry("SUBSTITUTION_LINES", 2),
            Map.entry("HALF_TIME_LINES", 0),
            Map.entry("TACTICS_USER_LINES", 0),
            Map.entry("TACTICS_PUSH", 0),
            Map.entry("TACTICS_ALL_OUT", 0),
            Map.entry("TACTICS_PROTECT", 0),
            Map.entry("TACTICS_PARK_THE_BUS", 0),
            Map.entry("TACTICS_NORMAL", 0),
            Map.entry("OPP_GOAL", 2),
            Map.entry("OPP_GOAL_ASSISTED", 3),
            Map.entry("OPP_PENALTY_GOAL", 2),
            Map.entry("OPP_FREE_KICK_GOAL", 2),
            Map.entry("OPP_CORNER_GOAL", 2));

    @Test
    void arityMapCoversExactlyEveryPoolFieldInTheClass() {
        Set<String> actualPoolFields = Arrays.stream(LiveCommentary.class.getDeclaredFields())
                .filter(f -> f.getType().equals(String[].class))
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertThat(ARITY_BY_POOL_FIELD.keySet()).containsExactlyInAnyOrderElementsOf(actualPoolFields);
    }

    @Test
    void everyPoolLinesPlaceholderCountNeverExceedsItsCallArity() throws Exception {
        for (Map.Entry<String, Integer> entry : ARITY_BY_POOL_FIELD.entrySet()) {
            Field field = LiveCommentary.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            String[] lines = (String[]) field.get(null);
            int arity = entry.getValue();
            assertThat(lines).as("pool %s must have at least one line", entry.getKey()).isNotEmpty();
            for (String line : lines) {
                int placeholders = line.split("%s", -1).length - 1;
                assertThat(placeholders)
                        .as("pool %s line [%s] has %d '%%s' placeholders but its call site passes %d args",
                                entry.getKey(), line, placeholders, arity)
                        .isLessThanOrEqualTo(arity);
            }
        }
    }

    @Test
    void theFiveNewOpponentPoolsEachHaveAtLeastFourLines() throws Exception {
        for (String field : Set.of("OPP_GOAL", "OPP_GOAL_ASSISTED", "OPP_PENALTY_GOAL",
                "OPP_FREE_KICK_GOAL", "OPP_CORNER_GOAL")) {
            Field f = LiveCommentary.class.getDeclaredField(field);
            f.setAccessible(true);
            String[] lines = (String[]) f.get(null);
            assertThat(lines.length).as("pool %s should have 4+ lines", field).isGreaterThanOrEqualTo(4);
        }
    }

    @Test
    void anOpponentGoalWithoutAnAssistUsesTheSadOppGoalPoolAndNamesTheScorer() {
        String result = LiveCommentary.of(EventType.GOAL, "Rival", null, 60, null,
                false, false, false, true, "Avaí");
        assertThat(result).contains("Rival");
        assertThat(result).isIn(
                "Gol deles... a zaga bate cabeça e o Rival aproveita.",
                "Rival marca para o Avaí e o estádio fica em silêncio.",
                "Vazou atrás! Rival aparece livre e balança as redes contra nós.",
                "Golpe duro... Rival marca pelo Avaí e a torcida baixa a cabeça.",
                "Que baque, torcida... Rival não perdoa e é gol do adversário.",
                "Doeu... Rival decreta o gol do Avaí e o silêncio toma conta da arquibancada.");
    }

    @Test
    void anOpponentGoalWithAnAssistUsesTheSadOppGoalAssistedPool() {
        String result = LiveCommentary.of(EventType.GOAL, "Rival", "Companheiro", 60, null,
                false, false, false, true, "Avaí");
        assertThat(result).contains("Rival").contains("Companheiro");
        assertThat(result).isIn(
                "Bola alçada na área... Companheiro cruza, Rival cabeceia e é gol deles.",
                "Assistência amarga: Companheiro serve Rival, que não perdoa contra nós.",
                "Companheiro enxergou o espaço, tocou pra Rival do Avaí, e é gol do adversário.",
                "Jogada coletiva deles: Companheiro cruza, Rival empurra pro gol do Avaí.");
    }

    @Test
    void anOpponentPenaltyGoalUsesTheSadOppPenaltyPool() {
        String result = LiveCommentary.of(EventType.PENALTY_GOAL, "Rival", null, 60, null,
                false, false, false, true, "Avaí");
        assertThat(result).isIn(
                "Pênalti pra eles... Rival não desperdiça.",
                "É pênalti a favor do adversário... Rival bate e marca. Que sufoco.",
                "Vai pra bola... Rival converte o pênalti pelo Avaí e complica nossa vida.",
                "Doeu... Rival cobra com categoria e amplia o placar do Avaí.");
    }

    @Test
    void anOpponentFreeKickGoalUsesTheSadOppFreeKickPool() {
        String result = LiveCommentary.of(EventType.FREE_KICK_GOAL, "Rival", null, 60, null,
                false, false, false, true, "Avaí");
        assertThat(result).isIn(
                "Falta perigosa... Rival bate e balança as redes contra nós.",
                "Cobrança certeira do adversário: Rival acerta o ângulo e marca pelo Avaí.",
                "A barreira nem pulou... Rival manda uma bomba e é gol deles.",
                "Que pontaria... Rival cobra a falta e decreta mais um gol do Avaí.");
    }

    @Test
    void anOpponentCornerGoalUsesTheSadOppCornerPool() {
        String result = LiveCommentary.of(EventType.CORNER_GOAL, "Rival", "Batedor", 60, null,
                false, false, false, true, "Avaí");
        assertThat(result).isIn(
                "Escanteio cobrado, Rival sobe mais alto e cabeceia pra dentro. Gol deles.",
                "Bola na área, Rival testa e amplia o placar do Avaí.",
                "Subiu sozinho... Rival cabeceia e balança as redes contra nós.",
                "Que falha na marcação... Rival aproveita o escanteio e marca pelo Avaí.");
    }

    @Test
    void theExistingSignatureDelegatesToTheOverloadWithOpponentGoalFalse() {
        String viaOldSignature = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false);
        String viaNewSignatureFalse = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null,
                false, false, false, false, null);
        assertThat(viaOldSignature).isEqualTo(viaNewSignatureFalse);
    }

    @Test
    void managedGoalCommentaryIsByteForByteUnchangedRegardlessOfTheNewOverload() {
        // Regression: the managed (non-opponent) path must keep producing the exact same lines —
        // membership in the original GOAL pool, never anything from OPP_GOAL.
        for (int minute = 1; minute <= 90; minute++) {
            String result = LiveCommentary.of(EventType.GOAL, "Otero", null, minute, null,
                    false, false, false, false, null);
            assertThat(result).isIn(
                    "GOOOOOOOOOOOOOOOOOOL! Otero balança as redes!",
                    "Otero marca! É GOOOOOOOOOOOOOOOOOOL!!! E que golaaaaçoooo!",
                    "Otero aparece na área e não perdoa! É GOOOOOOOOOOOOOOOOOOL!!!",
                    "GOOOOL! Otero! Eu tô emocionado, gente, que golaço impressionante!",
                    "É GOOOOOOOOOOOOOOOOOOL!!! Bateu, tremeu a rede e caiu o estádio: golaço de Otero!",
                    "Joga a luva, goleirão! Otero manda uma patada e é gooooooooooool!");
        }
    }

    // Variant index = minute + type.ordinal() mod array length; every assertion below checks
    // membership in the full current pool rather than a fixed index or exact string, so
    // reordering or rewording variants (an ongoing content pass) never breaks these tests.

    @Test
    void userTacticsChangeKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, null, false, false, false)).isIn(
                "O treinador mexe no time, vamos ver o que muda em campo!",
                "Mudança tática do treinador. Vamo ver se essa mudança pega bem",
                "Ajuste na estratégia — o comandante não fica parado no banco, hein!");
    }

    @Test
    void aiTacticsChangeGetsPostureSpecificPool() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PUSH", false, false, false)).isIn(
                "Eles sobem as linhas e vão pra cima. Segura aí, professor!",
                "O adversário se lança ao ataque. Agora esse relógio tem que passar voando",
                "Postura ofensiva do adversário, que quer pressionar lá na frente!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "ALL_OUT", false, false, false)).isIn(
                "É tudo ou nada agora! O adversário joga por tudo! Essa é hora de apostar no nosso contra-ataque!",
                "Tudo ou nada do adversário: 4-2-4 ofensivo!",
                "Sem medo de perder: o rival lança todo mundo no ataque! Te segura aí, Tigre!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PROTECT", false, false, false)).isIn(
                "O rival se fecha e tenta administrar a vantagem, temos que achar um jeito de penetrar nessa retranca e marcar um gol logo",
                "O adversário recua para segurar o resultado. Hora de avançarmos as linhas e pressioná-los no campo de defesa",
                "Eles priorizam a marcação para não sofrer o empate, mas nós vamos com tudo!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PARK_THE_BUS", false, false, false)).isIn(
                "Ônibus estacionado! Eles agora só pensam em defender! Tem que furar essa barreira",
                "Adversário fecha-se na defesa: 5-4-1. Tem que encurralar os caras, vamo pra cima!",
                "Ninguém passa! O rival montou uma muralha lá atrás! Bem coisa de time pequeno!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "NORMAL", false, false, false)).isIn(
                "O comandante rival ajusta as peças no tabuleiro!",
                "O adversário reorganiza a equipe. Postura mais equilibrada agora",
                "Reorganização no adversário, buscando equilíbrio dentro de campo.");
    }

    @Test
    void nonTacticsEventsIgnoreTheDetailParameter() {
        assertThat(LiveCommentary.of(EventType.SUBSTITUTION, "Sai", "Entra", 70, null, false, false, false)).isIn(
                "Substituição! Entra Entra, sai Sai. Vamo que vamo!",
                "Troca no time: entra Entra no lugar de Sai. Fôlego novo pra reta final!",
                "Mudança do treinador: Entra entra, Sai sai de campo aplaudido, bem merecidamente!",
                "Aí vem a mexida! Entra pisa no gramado no lugar de Sai.");
    }

    @Test
    void penaltyGoalMissAndFreeKickLines() {
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, false, false, false)).isIn(
                "Pênalti! Otero converte com categoria!",
                "É PÊÊÊNAAALTII!!! Otero bate... É GOOOOOOOOOOOLLLL!",
                "É GOOOOOOOOOOOLLLL! Que cobrança maravilhooosaaa! Bateu forte e com gosto, Otero não erra da marca da cal!",
                "Vai pra bola... É GOOOOOOOOOLLLLLL!!! Goleiro pra um lado e bola para o outro, Otero converte de pênalti!");
        assertThat(LiveCommentary.of(EventType.PENALTY_MISSED, "Otero", null, 60, null, false, false, false)).isIn(
                "É PÊNALTI... Otero vai para a marca da cal eeee.... PERDEEEUUU! O que é isso meu filho? Essa aí tu mandou lá no Maracajá!",
                "Otero vai pra cobrança do pênalti eeeee... perdeeeeeuuuu! O goleiro foi buscar a bola lá na Seis de Janeiro!",
                "É PÊÊÊNAAALTII!!! Que sufoco... Otero bateu em cima do goleiro e perdeu a chance de ouro! Esse aí não serve nem pra jogar o praião",
                "É PÊÊÊNAAALTII!!! É agora, correu pra bola eee.... PERDEEEEEEEUUUU! O que é isso? Otero pelo amor de Deus, assim você não merece aumento");
        assertThat(LiveCommentary.of(EventType.FREE_KICK_GOAL, "Otero", null, 60, null, false, false, false)).isIn(
                "Falta perigosa, Otero na bola... bateeeeeeuuuu.... GOLAAAAAAÇOOOOO!!! GOOOOOOOOOOOOLLL! A bola foi por cima da barreira e morreu no fundo do gol!",
                "Mais uma falta perigosa, Otero correu... bateeeu... JOOOOGAAA A LUUUVA GOLEIRÃÃÃOOOO!!! GOOOOOOOOOOOLLLLLLL!!! Isso não foi uma falta, mas sim uma obra de arte!",
                "Pra ele ali é meio gol... bateeeeeuu... ÉÉÉÉÉÉÉÉÉÉ GOLAAAAÇOOOOOOOOO! Otero acerta o ângulo e vai pra torcidaa! Cuidado pra não tomar cartão amarelo!",
                "Falta cobrada por Otero... É GOOOOOOOOOOOOOOL! JOGA A LUUUUUVA GOLEIRÃOOOOOO");
    }

    @Test
    void cornerGoalNamesTakerAndScorer() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", "Batedor", 70, null, false, false, false)).isIn(
                "Batedor levanta na área, e Zagueiro sobe mais alto que todos para cabecear: É GOOOOOL!",
                "Escanteio de Batedor, Zagueiro sobe e cabeceia: GOL!",
                "Cobrança de Batedor, e Zagueiro aparece de cabeça pra estufar a rede!",
                "Batedor bate o escanteio, Zagueiro sobe sozinho e manda pra dentro! Que jogada de bola parada!");
    }

    @Test
    void cornerGoalFallsBackToOneNameWhenTakerMissingOrScoredHimself() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", null, 70, null, false, false, false)).isIn(
                "Subiu mais que todo mundo! Zagueiro cabeceia e é GOOOOOL no escanteio!",
                "GOL de escanteio! Zagueiro aparece na área e não perdoa!",
                "Bola levantada na área, Zagueiro testa e balança as redes!",
                "Escanteio, cabeçada e GOL! Zagueiro não perdoa dentro da pequena área!");
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Batedor", "Batedor", 70, null, false, false, false)).isIn(
                "Subiu mais que todo mundo! Batedor cabeceia e é GOOOOOL no escanteio!",
                "GOL de escanteio! Batedor aparece na área e não perdoa!",
                "Bola levantada na área, Batedor testa e balança as redes!",
                "Escanteio, cabeçada e GOL! Batedor não perdoa dentro da pequena área!");
    }

    @Test
    void comebackGoalAppendsTheSignatureLineAfterTheNormalGoalDescriptionForEveryGoalFamilyType() {
        String baseGoal = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, true, false, false))
                .isEqualTo(baseGoal + " Homens de pouca fé... homens de muita fé!");

        String basePenalty = LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, true, false, false))
                .isEqualTo(basePenalty + " Homens de pouca fé... homens de muita fé!");

        String baseCorner = LiveCommentary.of(EventType.CORNER_GOAL, "Otero", "Diego", 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Otero", "Diego", 60, null, true, false, false))
                .isEqualTo(baseCorner + " Homens de pouca fé... homens de muita fé!");
    }

    @Test
    void comebackGoalFlagIsIgnoredForNonGoalTypes() {
        assertThat(LiveCommentary.of(EventType.YELLOW_CARD, "Otero", null, 60, null, true, false, false)).isIn(
                "O juiz não pensou duas vezes: é amareeeelo pra Otero!",
                "Otero entra forte e o juiz não perdoa: cartão amarelo!",
                "Cartão amarelo pra Otero. Cuidado, hein, que com mais um ele desfalca o time!",
                "Otero exagerou na entrada e o cartão veio na hora! Se liga aí, moleque!");
    }

    @Test
    void hypeGoalAppendsTheSignatureLineAfterTheNormalGoalDescription() {
        String baseGoal = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, true, false))
                .isEqualTo(baseGoal
                        + " Rapaziada eu vou dizer uma coisa pra vocês, esse Criciúma já ta me cheirando a libertadores... Creeedo coisa lindaaa!!");
    }

    @Test
    void hypeGoalFlagIsIgnoredForNonGoalTypes() {
        assertThat(LiveCommentary.of(EventType.YELLOW_CARD, "Otero", null, 60, null, false, true, false)).isIn(
                "O juiz não pensou duas vezes: é amareeeelo pra Otero!",
                "Otero entra forte e o juiz não perdoa: cartão amarelo!",
                "Cartão amarelo pra Otero. Cuidado, hein, que com mais um ele desfalca o time!",
                "Otero exagerou na entrada e o cartão veio na hora! Se liga aí, moleque!");
    }

    @Test
    void comebackAndHypeBothTrueAppendBothSignatureLinesInOrder() {
        String baseGoal = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false);
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, true, true, false))
                .isEqualTo(baseGoal
                        + " Homens de pouca fé... homens de muita fé!"
                        + " Rapaziada eu vou dizer uma coisa pra vocês, esse Criciúma já ta me cheirando a libertadores... Creeedo coisa lindaaa!!");
    }

    @Test
    void losingStreakOverridesTheHalfTimeLine() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, false, true))
                .isEqualTo("E não adianta me ligar que eu vou mandar a merda.");
    }

    @Test
    void halfTimeWithoutAStreakKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, false, false)).isIn(
                "Apita o juiz e o primeiro tempo chega ao fim! Bora rever o que deu e o que não deu",
                "Intervalo — fim do primeiro tempo. Hora de ajustar as peças, professor!",
                "Hora do cafezinho, torcida! Os times vão pro vestiário e a gente já volta com mais",
                "Fim de primeiro tempo, e olha que jogo emocionante estamos tendo hoje!");
    }

    @Test
    void anAssistedGoalUsesTheTwoNamePoolNamingBothPlayers() {
        String result = LiveCommentary.of(EventType.GOAL, "Otero", "Diego", 60, null, false, false, false);
        assertThat(result).contains("Otero").contains("Diego");
    }

    @Test
    void anUnassistedGoalKeepsUsingTheOneNamePool() {
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false, false)).isIn(
                "GOOOOOOOOOOOOOOOOOOL! Otero balança as redes!",
                "Otero marca! É GOOOOOOOOOOOOOOOOOOL!!! E que golaaaaçoooo!",
                "Otero aparece na área e não perdoa! É GOOOOOOOOOOOOOOOOOOL!!!",
                "GOOOOL! Otero! Eu tô emocionado, gente, que golaço impressionante!",
                "É GOOOOOOOOOOOOOOOOOOL!!! Bateu, tremeu a rede e caiu o estádio: golaço de Otero!",
                "Joga a luva, goleirão! Otero manda uma patada e é gooooooooooool!");
    }

    @Test
    void aSecondYellowRedUsesTheDedicatedPool() {
        assertThat(LiveCommentary.of(EventType.RED_CARD, "Otero", null, 60,
                LiveTimelineEvent.SECOND_YELLOW_DETAIL, false, false, false)).isIn(
                "Segundo amarelo pra Otero e consequentemente o VERMELHO! Tá expulso, vai mais cedo pro chuveiro!",
                "Otero já tinha amarelo e fez outra! Segundo cartão amarelo, vermelho na sequência: RUA!",
                "Não podia fazer essa falta, Otero! O juiz mostra o segundo amarelo e depois o vermelho: EXPULSO!",
                "Bobeira de Otero! Segundo amarelo no jogo, o vermelho vem junto e o time fica com um a menos!");
    }

    @Test
    void aStraightRedKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.RED_CARD, "Otero", null, 60, null, false, false, false)).isIn(
                "É VERMELHOOOO! Otero está expulso e vai direto pro vestiário!",
                "Otero recebe o vermelho e deixa o time com um a menos. Agora vai ter que suar a camisa!",
                "Vermelho direto! Otero vai pro chuveiro mais cedo, e olha que sufoco pro time que fica reduzido!",
                "Não teve jeito, Otero: o juiz mostrou o vermelho e a arquibancada foi à loucura!");
    }
}
