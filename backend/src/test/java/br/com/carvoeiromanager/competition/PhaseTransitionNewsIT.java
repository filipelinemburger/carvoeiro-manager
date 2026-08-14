package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the phase-transition news {@link PhaseResolver} emits alongside materialization
 * (Task 12, spec §7.5): PHASE_QUALIFIED/PHASE_ELIMINATED when a round-robin phase ends,
 * KNOCKOUT_DRAW when a knockout phase's ties are drawn, CHAMPION when the final is decided.
 *
 * <p>Builds its own minimal 8-club competition directly through the repositories, same approach
 * as {@link PhaseResolverTest}: a ROUND_ROBIN first phase (qualifiers = 4, so the bottom half is
 * eliminated) feeding a KNOCKOUT "Quartas de final" phase, feeding a KNOCKOUT "Final" phase with
 * nothing after it — the natural place for a champion to be crowned. Results are rigged so the
 * lower club index always wins, giving a fully predictable bracket: club 0 is undefeated
 * throughout and ends up champion.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class PhaseTransitionNewsIT {

    @Autowired PhaseResolver phaseResolver;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;

    private static final int MEMBERS = 8;
    private static final int ROUNDS = MEMBERS - 1;
    private static final int QUALIFIERS = 4;

    private record Scenario(SaveGame save, UUID competitionId, UUID firstPhaseId, UUID quarterFinalPhaseId,
                            UUID finalPhaseId, SeasonEvent lastFirstPhaseEvent,
                            SeasonEvent firstQuarterFinalEvent, SeasonEvent lastQuarterFinalEvent,
                            SeasonEvent firstFinalEvent, SeasonEvent lastFinalEvent,
                            List<UUID> clubIds) {
        UUID saveGameId() { return save.getId(); }
    }

    private Scenario buildScenario(String label) {
        User user = User.create(label + "_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGame save = SaveGame.create(user.getId(), 2026);
        save.setCurrentDate(LocalDate.of(2026, 1, 15));
        saveGameRepository.save(save);
        UUID saveGameId = save.getId();

        List<UUID> clubIds = new ArrayList<>();
        for (int i = 0; i < MEMBERS; i++) {
            Club club = Club.create(saveGameId, label + " Club " + i, "C" + i);
            clubRepository.save(club);
            clubIds.add(club.getId());
        }

        Competition competition = Competition.create(saveGameId, label + " Cup", "CUP", 2026, ROUNDS + 4);
        competitionRepository.save(competition);
        for (UUID clubId : clubIds) {
            competitionClubRepository.save(CompetitionClub.create(saveGameId, competition.getId(), clubId));
        }

        CompetitionPhase firstPhase = CompetitionPhase.create(saveGameId, competition.getId(), 0,
                "Primeira Fase", PhaseKind.ROUND_ROBIN, 1, ROUNDS, QUALIFIERS);
        competitionPhaseRepository.save(firstPhase);
        CompetitionPhase quarterFinalPhase = CompetitionPhase.create(saveGameId, competition.getId(), 1,
                "Quartas de final", PhaseKind.KNOCKOUT, 2, 2, null);
        competitionPhaseRepository.save(quarterFinalPhase);
        CompetitionPhase finalPhase = CompetitionPhase.create(saveGameId, competition.getId(), 2,
                "Final", PhaseKind.KNOCKOUT, 2, 2, null);
        competitionPhaseRepository.save(finalPhase);

        LocalDate seasonStart = LocalDate.of(2026, 1, 15);
        FixtureScheduler scheduler = new FixtureScheduler();
        for (ScheduledFixture s : scheduler.singleRoundRobin(MEMBERS)) {
            UUID home = clubIds.get(s.homeIndex());
            UUID away = clubIds.get(s.awayIndex());
            LocalDate date = seasonStart.plusDays((long) s.round() * 5);
            Fixture fx = Fixture.create(saveGameId, competition.getId(), s.round(), home, away, date);
            fx.setPhaseId(firstPhase.getId());
            // Deterministic result: the lower club index always wins 2-0.
            if (s.homeIndex() < s.awayIndex()) {
                fx.recordResult(2, 0);
            } else {
                fx.recordResult(0, 2);
            }
            fixtureRepository.save(fx);
        }

        List<SeasonEvent> firstPhaseEvents = new ArrayList<>();
        for (int r = 0; r < ROUNDS; r++) {
            LocalDate date = seasonStart.plusDays((long) r * 5);
            SeasonEvent event = SeasonEvent.createPhased(saveGameId, date, competition.getId(),
                    firstPhase.getId(), r, 0);
            seasonEventRepository.save(event);
            firstPhaseEvents.add(event);
        }
        for (int r = 0; r < ROUNDS - 1; r++) {
            firstPhaseEvents.get(r).markProcessed();
            seasonEventRepository.save(firstPhaseEvents.get(r));
        }
        SeasonEvent lastFirstPhaseEvent = firstPhaseEvents.get(ROUNDS - 1);

        List<SeasonEvent> quarterFinalEvents = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            LocalDate date = seasonStart.plusDays((long) (ROUNDS + r) * 5);
            SeasonEvent event = SeasonEvent.createPhased(saveGameId, date, competition.getId(),
                    quarterFinalPhase.getId(), ROUNDS + r, 0);
            seasonEventRepository.save(event);
            quarterFinalEvents.add(event);
        }

        List<SeasonEvent> finalEvents = new ArrayList<>();
        for (int r = 0; r < 2; r++) {
            LocalDate date = seasonStart.plusDays((long) (ROUNDS + 2 + r) * 5);
            SeasonEvent event = SeasonEvent.createPhased(saveGameId, date, competition.getId(),
                    finalPhase.getId(), ROUNDS + 2 + r, 0);
            seasonEventRepository.save(event);
            finalEvents.add(event);
        }

        return new Scenario(save, competition.getId(), firstPhase.getId(), quarterFinalPhase.getId(),
                finalPhase.getId(), lastFirstPhaseEvent, quarterFinalEvents.get(0), quarterFinalEvents.get(1),
                finalEvents.get(0), finalEvents.get(1), clubIds);
    }

    /** Scores every tie of a just-materialized phase so the higher seed always wins 2-0 on
     *  aggregate, then marks the phase's first reserved event processed (the second is left for
     *  the caller to feed into {@code resolveIfPhaseComplete}). */
    private void higherSeedWinsEveryTie(UUID saveGameId, UUID phaseId, SeasonEvent firstReservedEvent) {
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndPhaseId(saveGameId, phaseId);
        Map<Integer, List<Fixture>> byTieOrder = fixtures.stream()
                .collect(Collectors.groupingBy(Fixture::getTieOrder));
        for (List<Fixture> legs : byTieOrder.values()) {
            List<Fixture> sorted = legs.stream().sorted(Comparator.comparing(Fixture::getLeg)).toList();
            Fixture leg1 = sorted.get(0); // lower(home) vs higher(away)
            Fixture leg2 = sorted.get(1); // higher(home) vs lower(away)
            leg1.recordResult(0, 1);
            leg2.recordResult(1, 0);
            fixtureRepository.save(leg1);
            fixtureRepository.save(leg2);
        }
        firstReservedEvent.markProcessed();
        seasonEventRepository.save(firstReservedEvent);
    }

    private String clubName(UUID clubId) {
        return clubRepository.findById(clubId).orElseThrow().getName();
    }

    @Test
    void qualifyingForTheKnockoutGeneratesPhaseQualifiedNews() {
        // Club 0 always wins -> comfortably inside the top-4 qualifiers.
        Scenario s = buildScenario("qualify");
        Club managed = clubRepository.findById(s.clubIds().get(0)).orElseThrow();
        s.save().setManagedClubId(managed.getId());
        saveGameRepository.save(s.save());

        boolean resolved = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());
        assertThat(resolved).isTrue();

        List<NewsItem> news = newsItemRepository.findBySaveGameIdOrderBySeqDesc(s.saveGameId());
        assertThat(news).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("PHASE_QUALIFIED");
            assertThat(n.getPayload()).contains("Quartas de final");
        });
    }

    @Test
    void missingOutOnTheKnockoutGeneratesPhaseEliminatedNews() {
        // Club 7 always loses -> outside the top-4 qualifiers.
        Scenario s = buildScenario("eliminate");
        Club managed = clubRepository.findById(s.clubIds().get(7)).orElseThrow();
        s.save().setManagedClubId(managed.getId());
        saveGameRepository.save(s.save());

        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());

        List<NewsItem> news = newsItemRepository.findBySaveGameIdOrderBySeqDesc(s.saveGameId());
        assertThat(news).anySatisfy(n -> assertThat(n.getType()).isEqualTo("PHASE_ELIMINATED"));
        assertThat(news).noneMatch(n -> n.getType().equals("PHASE_QUALIFIED"));
    }

    @Test
    void theKnockoutDrawNamesTheManagedClubsOpponent() {
        Scenario s = buildScenario("draw");
        UUID managedClubId = s.clubIds().get(0);
        s.save().setManagedClubId(managedClubId);
        saveGameRepository.save(s.save());

        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());

        // knockoutPairings(4): tie 0 = seed0 vs seed3 -> club 0's QF opponent is seed index 3 of
        // the qualifiers, i.e. club index 3 (standings order == club index order here).
        String opponentName = clubName(s.clubIds().get(3));

        List<NewsItem> news = newsItemRepository.findBySaveGameIdOrderBySeqDesc(s.saveGameId());
        assertThat(news).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("KNOCKOUT_DRAW");
            assertThat(n.getPayload()).contains(opponentName);
        });
    }

    @Test
    void theChampionIsAnnouncedWhenTheFinalIsDecided() {
        Scenario s = buildScenario("champion");
        UUID managedClubId = s.clubIds().get(0);
        s.save().setManagedClubId(managedClubId);
        saveGameRepository.save(s.save());

        // First phase ends -> quarterfinal materializes (and PHASE_QUALIFIED/KNOCKOUT_DRAW fire).
        boolean qfMaterialized = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());
        assertThat(qfMaterialized).isTrue();

        // Play out the quarterfinal (higher seed always wins) -> the final materializes.
        higherSeedWinsEveryTie(s.saveGameId(), s.quarterFinalPhaseId(), s.firstQuarterFinalEvent());
        boolean finalMaterialized = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastQuarterFinalEvent());
        assertThat(finalMaterialized).isTrue();

        // Play out the final (higher seed wins again) -> club 0, undefeated throughout, is champion.
        higherSeedWinsEveryTie(s.saveGameId(), s.finalPhaseId(), s.firstFinalEvent());
        boolean resolved = phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFinalEvent());
        // No phase follows the final, so nothing is materialized -- but the champion is decided.
        assertThat(resolved).isFalse();

        String championName = clubName(managedClubId);
        String competitionName = competitionRepository.findById(s.competitionId()).orElseThrow().getName();
        List<NewsItem> news = newsItemRepository.findBySaveGameIdOrderBySeqDesc(s.saveGameId());
        assertThat(news).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("CHAMPION");
            // The competition leads the message, so two champions crowned in one season are
            // distinguishable. Asserted as the whole string: a `contains(championName)` passes
            // just as well against the old un-prefixed "X é o campeão!".
            assertThat(n.getPayload()).contains(competitionName + ": " + championName + " é o campeão!");
        });
    }

    /**
     * The behaviour Task 9 unlocked: a phased competition the managed club has nothing to do with
     * still resolves its own knockouts, so its champion is announced too. Nothing pinned that the
     * announcement identifies WHICH competition — which is the whole point once a season crowns
     * more than one champion. The managed club here is an outsider, not a member of the
     * competition at all, exactly as the player's club is to Série C.
     */
    @Test
    void aBackgroundCompetitionsChampionIsAnnouncedWithItsOwnName() {
        Scenario s = buildScenario("background");
        Club outsider = Club.create(s.saveGameId(), "Outsider FC", "OUT");
        clubRepository.save(outsider);
        s.save().setManagedClubId(outsider.getId());
        saveGameRepository.save(s.save());

        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFirstPhaseEvent());
        higherSeedWinsEveryTie(s.saveGameId(), s.quarterFinalPhaseId(), s.firstQuarterFinalEvent());
        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastQuarterFinalEvent());
        higherSeedWinsEveryTie(s.saveGameId(), s.finalPhaseId(), s.firstFinalEvent());
        phaseResolver.resolveIfPhaseComplete(s.save(), s.lastFinalEvent());

        String championName = clubName(s.clubIds().get(0));
        String competitionName = competitionRepository.findById(s.competitionId()).orElseThrow().getName();
        List<NewsItem> news = newsItemRepository.findBySaveGameIdOrderBySeqDesc(s.saveGameId());
        assertThat(news).anySatisfy(n -> {
            assertThat(n.getType()).isEqualTo("CHAMPION");
            assertThat(n.getPayload()).contains(competitionName + ": " + championName + " é o campeão!");
        });
        // The managed club is not a member, so it gets no qualified/eliminated card — the
        // champion announcement is genuinely un-gated, not a side effect of following the club.
        assertThat(news).noneMatch(n -> n.getType().equals("PHASE_QUALIFIED"));
        assertThat(news).noneMatch(n -> n.getType().equals("PHASE_ELIMINATED"));
    }
}
