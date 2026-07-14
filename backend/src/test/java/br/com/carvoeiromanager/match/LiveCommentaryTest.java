package br.com.carvoeiromanager.match;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LiveCommentaryTest {

    // Variant index = minute + type.ordinal() mod array length; every assertion below checks
    // membership in the full current pool rather than a fixed index or exact string, so
    // reordering or rewording variants (an ongoing content pass) never breaks these tests.

    @Test
    void userTacticsChangeKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, null, false, false)).isIn(
                "O treinador mexe no time, vamos ver o que muda em campo!",
                "Mudança tática do treinador. Vamo ver se essa mudança pega bem",
                "Ajuste na estratégia — o comandante não fica parado no banco, hein!");
    }

    @Test
    void aiTacticsChangeGetsPostureSpecificPool() {
        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PUSH", false, false)).isIn(
                "Eles sobem as linhas e vão pra cima. Segura aí, professor!",
                "O adversário se lança ao ataque. Agora esse relógio tem que passar voando",
                "Postura ofensiva do adversário, que quer pressionar lá na frente!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "ALL_OUT", false, false)).isIn(
                "É tudo ou nada agora! O adversário joga por tudo! Essa é hora de apostar no nosso contra-ataque!",
                "Tudo ou nada do adversário: 4-2-4 ofensivo!",
                "Sem medo de perder: o rival lança todo mundo no ataque! Te segura aí, Tigre!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PROTECT", false, false)).isIn(
                "O rival se fecha e tenta administrar a vantagem, temos que achar um jeito de penetrar nessa retranca e marcar um gol logo",
                "O adversário recua para segurar o resultado. Hora de avançarmos as linhas e pressioná-los no campo de defesa",
                "Eles priorizam a marcação para não sofrer o empate, mas nós vamos com tudo!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "PARK_THE_BUS", false, false)).isIn(
                "Ônibus estacionado! Eles agora só pensam em defender! Tem que furar essa barreira",
                "Adversário fecha-se na defesa: 5-4-1. Tem que encurralar os caras, vamo pra cima!",
                "Ninguém passa! O rival montou uma muralha lá atrás! Bem coisa de time pequeno!");

        assertThat(LiveCommentary.of(EventType.TACTICS_CHANGE, "Fulano", null, 60, "NORMAL", false, false)).isIn(
                "O comandante rival ajusta as peças no tabuleiro!",
                "O adversário reorganiza a equipe. Postura mais equilibrada agora",
                "Reorganização no adversário, buscando equilíbrio dentro de campo.");
    }

    @Test
    void nonTacticsEventsIgnoreTheDetailParameter() {
        assertThat(LiveCommentary.of(EventType.SUBSTITUTION, "Sai", "Entra", 70, null, false, false)).isIn(
                "Substituição! Entra Entra, sai Sai. Vamo que vamo!",
                "Troca no time: entra Entra no lugar de Sai. Fôlego novo pra reta final!",
                "Mudança do treinador: Entra entra, Sai sai de campo aplaudido, bem merecidamente!",
                "Aí vem a mexida! Entra pisa no gramado no lugar de Sai.");
    }

    @Test
    void penaltyGoalMissAndFreeKickLines() {
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, false, false)).isIn(
                "Pênalti! Otero converte com categoria!",
                "É PÊÊÊNAAALTII!!! Otero bate... É GOOOOOOOOOOOLLLL!",
                "É GOOOOOOOOOOOLLLL! Que cobrança maravilhooosaaa! Bateu forte e com gosto, Otero não erra da marca da cal!",
                "Vai pra bola... É GOOOOOOOOOLLLLLL!!! Goleiro pra um lado e bola para o outro, Otero converte de pênalti!");
        assertThat(LiveCommentary.of(EventType.PENALTY_MISSED, "Otero", null, 60, null, false, false)).isIn(
                "É PÊNALTI... Otero vai para a marca da cal eeee.... PERDEEEUUU! O que é isso meu filho? Essa aí tu mandou lá no Maracajá!",
                "Otero vai pra cobrança do pênalti eeeee... perdeeeeeuuuu! O goleiro foi buscar a bola lá na Seis de Janeiro!",
                "É PÊÊÊNAAALTII!!! Que sufoco... Otero bateu em cima do goleiro e perdeu a chance de ouro! Esse aí não serve nem pra jogar o praião",
                "É PÊÊÊNAAALTII!!! É agora, correu pra bola eee.... PERDEEEEEEEUUUU! O que é isso? Otero pelo amor de Deus, assim você não merece aumento");
        assertThat(LiveCommentary.of(EventType.FREE_KICK_GOAL, "Otero", null, 60, null, false, false)).isIn(
                "Falta perigosa, Otero na bola... bateeeeeeuuuu.... GOLAAAAAAÇOOOOO!!! GOOOOOOOOOOOOLLL! A bola foi por cima da barreira e morreu no fundo do gol!",
                "Mais uma falta perigosa, Otero correu... bateeeu... JOOOOGAAA A LUUUVA GOLEIRÃÃÃOOOO!!! GOOOOOOOOOOOLLLLLLL!!! Isso não foi uma falta, mas sim uma obra de arte!",
                "Pra ele ali é meio gol... bateeeeeuu... ÉÉÉÉÉÉÉÉÉÉ GOLAAAAÇOOOOOOOOO! Otero acerta o ângulo e vai pra torcidaa! Cuidado pra não tomar cartão amarelo!",
                "Falta cobrada por Otero... É GOOOOOOOOOOOOOOL! JOGA A LUUUUUVA GOLEIRÃOOOOOO");
    }

    @Test
    void cornerGoalNamesTakerAndScorer() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", "Batedor", 70, null, false, false)).isIn(
                "Batedor levanta na área, e Zagueiro sobe mais alto que todos para cabecear: É GOOOOOL!",
                "Escanteio de Batedor, Zagueiro sobe e cabeceia: GOL!",
                "Cobrança de Batedor, e Zagueiro aparece de cabeça pra estufar a rede!",
                "Batedor bate o escanteio, Zagueiro sobe sozinho e manda pra dentro! Que jogada de bola parada!");
    }

    @Test
    void cornerGoalFallsBackToOneNameWhenTakerMissingOrScoredHimself() {
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Zagueiro", null, 70, null, false, false)).isIn(
                "Subiu mais que todo mundo! Zagueiro cabeceia e é GOOOOOL no escanteio!",
                "GOL de escanteio! Zagueiro aparece na área e não perdoa!",
                "Bola levantada na área, Zagueiro testa e balança as redes!",
                "Escanteio, cabeçada e GOL! Zagueiro não perdoa dentro da pequena área!");
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Batedor", "Batedor", 70, null, false, false)).isIn(
                "Subiu mais que todo mundo! Batedor cabeceia e é GOOOOOL no escanteio!",
                "GOL de escanteio! Batedor aparece na área e não perdoa!",
                "Bola levantada na área, Batedor testa e balança as redes!",
                "Escanteio, cabeçada e GOL! Batedor não perdoa dentro da pequena área!");
    }

    @Test
    void comebackGoalAppendsTheSignatureLineAfterTheNormalGoalDescriptionForEveryGoalFamilyType() {
        String baseGoal = LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false);
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, true, false))
                .isEqualTo(baseGoal + " Homens de pouca fé... homens de muita fé!");

        String basePenalty = LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, false, false);
        assertThat(LiveCommentary.of(EventType.PENALTY_GOAL, "Otero", null, 60, null, true, false))
                .isEqualTo(basePenalty + " Homens de pouca fé... homens de muita fé!");

        String baseCorner = LiveCommentary.of(EventType.CORNER_GOAL, "Otero", "Diego", 60, null, false, false);
        assertThat(LiveCommentary.of(EventType.CORNER_GOAL, "Otero", "Diego", 60, null, true, false))
                .isEqualTo(baseCorner + " Homens de pouca fé... homens de muita fé!");
    }

    @Test
    void comebackGoalFlagIsIgnoredForNonGoalTypes() {
        assertThat(LiveCommentary.of(EventType.YELLOW_CARD, "Otero", null, 60, null, true, false)).isIn(
                "O juiz não pensou duas vezes: é amareeeelo pra Otero!",
                "Otero entra forte e o juiz não perdoa: cartão amarelo!",
                "Cartão amarelo pra Otero. Cuidado, hein, que com mais um ele desfalca o time!",
                "Otero exagerou na entrada e o cartão veio na hora! Se liga aí, moleque!");
    }

    @Test
    void losingStreakOverridesTheHalfTimeLine() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, true))
                .isEqualTo("E não adianta me ligar que eu vou mandar a merda.");
    }

    @Test
    void halfTimeWithoutAStreakKeepsTheGenericPool() {
        assertThat(LiveCommentary.of(EventType.HALF_TIME, null, null, 45, null, false, false)).isIn(
                "Apita o juiz e o primeiro tempo chega ao fim! Bora rever o que deu e o que não deu",
                "Intervalo — fim do primeiro tempo. Hora de ajustar as peças, professor!",
                "Hora do cafezinho, torcida! Os times vão pro vestiário e a gente já volta com mais",
                "Fim de primeiro tempo, e olha que jogo emocionante estamos tendo hoje!");
    }

    @Test
    void anAssistedGoalUsesTheTwoNamePoolNamingBothPlayers() {
        String result = LiveCommentary.of(EventType.GOAL, "Otero", "Diego", 60, null, false, false);
        assertThat(result).contains("Otero").contains("Diego");
    }

    @Test
    void anUnassistedGoalKeepsUsingTheOneNamePool() {
        assertThat(LiveCommentary.of(EventType.GOAL, "Otero", null, 60, null, false, false)).isIn(
                "GOOOOOOOOOOOOOOOOOOL! Otero balança as redes!",
                "Otero marca! É GOOOOOOOOOOOOOOOOOOL!!! E que golaaaaçoooo!",
                "Otero aparece na área e não perdoa! É GOOOOOOOOOOOOOOOOOOL!!!",
                "GOOOOL! Otero! Eu tô emocionado, gente, que golaço impressionante!",
                "É GOOOOOOOOOOOOOOOOOOL!!! Bateu, tremeu a rede e caiu o estádio: golaço de Otero!",
                "Joga a luva, goleirão! Otero manda uma patada e é gooooooooooool!");
    }
}
