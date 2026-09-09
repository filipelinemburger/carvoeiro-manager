package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring check for Task 10: an opponent-side goal reaching {@link LiveMatchService}
 * through the real kickoff/advance path must carry a sad OPP_* line naming the opponent club,
 * while every managed-side goal in the same run keeps naming the scorer as before (neutral pools
 * are covered byte-for-byte by LiveCommentaryTest; this IT only proves the caller wiring).
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class LiveMatchServiceOpponentGoalCommentaryIT {

    @Autowired LiveMatchService liveMatchService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired LineupService lineupService;
    @Autowired br.com.carvoeiromanager.player.PlayerRepository playerRepository;
    @Autowired ClubRepository clubRepository;

    private LiveMatchState playANewCareerToFullTime() {
        User user = User.create("live_opp_goal_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        UUID userId = user.getId();

        SaveGameResponse save = saveGameService.createCareer(userId);
        UUID saveGameId = save.id();
        UUID managedClubId = save.managedClubId();

        List<Player> bestXi = lineupService.bestXi(managedClubId, null, null);
        List<LineupStarter> starters = bestXi.stream()
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
        List<UUID> xiIds = bestXi.stream().map(Player::getId).toList();
        List<LineupStarter> bench = playerRepository.findByClubId(managedClubId).stream()
                .filter(p -> !xiIds.contains(p.getId()))
                .filter(Player::isEligible)
                .limit(3)
                .map(p -> new LineupStarter(p.getId(), RoleArchetype.defaultFor(p.getDetailedPosition()).name()))
                .collect(Collectors.toList());
        lineupService.setLineup(saveGameId, managedClubId, new SetLineupRequest("4-4-2", starters, bench), userId);

        liveMatchService.kickoff(saveGameId, userId);
        LiveMatchState state = null;
        for (int i = 0; i < 400 && (state == null || !"FULL_TIME".equals(state.status())); i++) {
            state = liveMatchService.advance(saveGameId, userId);
        }
        return state;
    }

    /** Every possible opponent-side commentary line for {@code type}, formatted with the real names. */
    private List<String> expectedOppLines(EventType type, String scorer, String assister, String club) {
        return switch (type) {
            case GOAL -> assister != null
                    ? List.of(
                            "Bola alçada na área... %s cruza, %s cabeceia e é gol deles.".formatted(assister, scorer),
                            "Assistência amarga: %s serve %s, que não perdoa contra nós.".formatted(assister, scorer),
                            "%s enxergou o espaço, tocou pra %s do %s, e é gol do adversário."
                                    .formatted(assister, scorer, club),
                            "Jogada coletiva deles: %s cruza, %s empurra pro gol do %s.".formatted(assister, scorer, club))
                    : List.of(
                            "Gol deles... a zaga bate cabeça e o %s aproveita.".formatted(scorer),
                            "%s marca para o %s e o estádio fica em silêncio.".formatted(scorer, club),
                            "Vazou atrás! %s aparece livre e balança as redes contra nós.".formatted(scorer),
                            "Golpe duro... %s marca pelo %s e a torcida baixa a cabeça.".formatted(scorer, club),
                            "Que baque, torcida... %s não perdoa e é gol do adversário.".formatted(scorer),
                            "Doeu... %s decreta o gol do %s e o silêncio toma conta da arquibancada.".formatted(scorer, club));
            case PENALTY_GOAL -> List.of(
                    "Pênalti pra eles... %s não desperdiça.".formatted(scorer),
                    "É pênalti a favor do adversário... %s bate e marca. Que sufoco.".formatted(scorer),
                    "Vai pra bola... %s converte o pênalti pelo %s e complica nossa vida.".formatted(scorer, club),
                    "Doeu... %s cobra com categoria e amplia o placar do %s.".formatted(scorer, club));
            case FREE_KICK_GOAL -> List.of(
                    "Falta perigosa... %s bate e balança as redes contra nós.".formatted(scorer),
                    "Cobrança certeira do adversário: %s acerta o ângulo e marca pelo %s.".formatted(scorer, club),
                    "A barreira nem pulou... %s manda uma bomba e é gol deles.".formatted(scorer),
                    "Que pontaria... %s cobra a falta e decreta mais um gol do %s.".formatted(scorer, club));
            case CORNER_GOAL -> List.of(
                    "Escanteio cobrado, %s sobe mais alto e cabeceia pra dentro. Gol deles.".formatted(scorer),
                    "Bola na área, %s testa e amplia o placar do %s.".formatted(scorer, club),
                    "Subiu sozinho... %s cabeceia e balança as redes contra nós.".formatted(scorer),
                    "Que falha na marcação... %s aproveita o escanteio e marca pelo %s.".formatted(scorer, club));
            default -> throw new IllegalArgumentException("no OPP_* pool for " + type);
        };
    }

    @Test
    void anOpponentGoalCarriesASadLineFromTheOppPoolWhileManagedGoalsKeepNamingTheScorer() {
        // Goal-scoring is randomized per career, so replay several full matches until one produces
        // at least one opponent-side goal — mirrors the "gather enough goals" pattern used by
        // FixtureSimulatorAssistFoulIT for other statistically-driven assertions.
        Optional<LiveMatchState> stateWithOpponentGoal = Optional.empty();
        for (int attempt = 0; attempt < 8 && stateWithOpponentGoal.isEmpty(); attempt++) {
            LiveMatchState state = playANewCareerToFullTime();
            assertThat(state).isNotNull();
            boolean hasOpponentGoal = state.events().stream()
                    .anyMatch(e -> EventType.valueOf(e.type()).isGoal() && e.home() != state.managedIsHome());
            if (hasOpponentGoal) {
                stateWithOpponentGoal = Optional.of(state);
            }
        }

        assertThat(stateWithOpponentGoal)
                .as("expected at least one opponent goal across 8 full matches")
                .isPresent();
        LiveMatchState state = stateWithOpponentGoal.get();
        String homeClubName = state.homeClubName();
        String awayClubName = state.awayClubName();

        for (LiveEventResponse event : state.events()) {
            EventType type = EventType.valueOf(event.type());
            // SET_PIECE_GOAL is legacy/never emitted by the live engine; skip if ever encountered.
            if (!type.isGoal() || type == EventType.SET_PIECE_GOAL) {
                continue;
            }
            boolean isOpponentGoal = event.home() != state.managedIsHome();
            if (isOpponentGoal) {
                String scoringClubName = event.home() ? homeClubName : awayClubName;
                List<String> expected = expectedOppLines(type, event.playerName(), event.playerInName(), scoringClubName);
                assertThat(event.text()).as("opponent %s at minute %d", type, event.minute()).isIn(expected);
            } else {
                assertThat(event.text()).contains(event.playerName());
            }
        }
    }
}
