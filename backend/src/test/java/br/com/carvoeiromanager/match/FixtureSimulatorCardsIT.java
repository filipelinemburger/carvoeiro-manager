package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class FixtureSimulatorCardsIT {

    @Autowired FixtureSimulator fixtureSimulator;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired MatchEventRepository matchEventRepository;

    private UUID saveGameId;
    private UUID managedClubId;

    @BeforeEach
    void setUp() {
        User user = User.create("instant_cards_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGameResponse save = saveGameService.createCareer(user.getId());
        saveGameId = save.id();
        managedClubId = save.managedClubId();
    }

    @Test
    void aSecondYellowAlwaysComesWithASameMinuteRedAndEndsThePlayersMatch() {
        Competition competition = competitionRepository.findBySaveGameId(saveGameId).get(0);
        // 160 AI fixtures make at least one same-player double booking statistically certain.
        // Arithmetic (BASE_CARD_RATE 0.02 -> 0.015, spec 2026-08-13 §9; RED_SHARE 0.06 unchanged;
        // ~11 on-pitch players per side, weighted by tackling+1 but treated as roughly uniform for
        // this order-of-magnitude estimate):
        //   - Yellow cards per side per 90 at aggression ~1.0: old 90*0.02*0.94 = 1.692,
        //     new 90*0.015*0.94 = 1.269.
        //   - A same-player double booking needs two of one side's yellows to land on the same of
        //     ~11 players — a birthday-paradox collision, so its rate scales with the SQUARE of the
        //     yellow rate: expected collisions per side per match ~= lambda^2 / (2*11).
        //     Old: 1.692^2 / 22 = 0.1302/side -> 0.2604/match (both sides).
        //     New: 1.269^2 / 22 = 0.0732/side -> 0.1464/match — a ~44% drop, matching (0.75)^2.
        //   - Over 80 fixtures the new rate gives an expected 0.1464*80 = 11.7 occurrences and
        //     P(zero) = e^-11.7 ~= 8e-6 — an order of magnitude thinner margin than the pre-cut
        //     P(zero) ~= 9e-10 at the same fixture count, on a repo that already suffers from
        //     flaky ITs. Doubling to 160 fixtures restores it: expected 0.1464*160 = 23.4
        //     occurrences, P(zero) = e^-23.4 ~= 7e-11 — safer than the original 80-fixture margin.
        List<Fixture> aiFixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, competition.getId()).stream()
                .filter(f -> !f.getHomeClubId().equals(managedClubId) && !f.getAwayClubId().equals(managedClubId))
                .limit(160)
                .toList();
        assertThat(aiFixtures).isNotEmpty();
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        fixtureSimulator.simulateRound(save, competition.getId(), aiFixtures);

        boolean sawSecondYellow = false;
        for (Fixture fixture : aiFixtures) {
            List<MatchEvent> events = matchEventRepository.findByFixtureIdIn(List.of(fixture.getId()));
            Map<UUID, List<MatchEvent>> yellows = events.stream()
                    .filter(e -> "YELLOW_CARD".equals(e.getType()))
                    .collect(Collectors.groupingBy(MatchEvent::getPlayerId));
            Map<UUID, List<MatchEvent>> reds = events.stream()
                    .filter(e -> "RED_CARD".equals(e.getType()))
                    .collect(Collectors.groupingBy(MatchEvent::getPlayerId));

            for (Map.Entry<UUID, List<MatchEvent>> en : yellows.entrySet()) {
                assertThat(en.getValue().size())
                        .as("a player can never collect more than two yellows in one match")
                        .isLessThanOrEqualTo(2);
                if (en.getValue().size() == 2) {
                    sawSecondYellow = true;
                    int secondYellowMinute = en.getValue().stream()
                            .mapToInt(MatchEvent::getMinute).max().orElseThrow();
                    List<MatchEvent> playerReds = reds.getOrDefault(en.getKey(), List.of());
                    assertThat(playerReds).as("two yellows must carry exactly one red").hasSize(1);
                    assertThat(playerReds.get(0).getMinute()).isEqualTo(secondYellowMinute);
                }
            }
            for (Map.Entry<UUID, List<MatchEvent>> en : reds.entrySet()) {
                assertThat(en.getValue()).as("at most one red per player").hasSize(1);
                int redMinute = en.getValue().get(0).getMinute();
                UUID sentOff = en.getKey();
                assertThat(events)
                        .as("a sent-off player must not be attributed any later event")
                        .noneMatch(e -> e.getMinute() > redMinute && sentOff.equals(e.getPlayerId()));
            }
        }
        assertThat(sawSecondYellow)
                .as("160 AI fixtures should produce at least one second-yellow send-off")
                .isTrue();
    }
}
