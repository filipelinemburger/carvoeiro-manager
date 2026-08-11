package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class SeasonCalendarGeneratorIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired CompetitionRepository competitionRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired CompetitionPhaseRepository competitionPhaseRepository;
    @Autowired FixtureRepository fixtureRepository;
    @Autowired SeasonEventRepository seasonEventRepository;
    @Autowired SeasonCalendarGenerator generator;

    // Populated fresh before each test by setUpManagedAndBackgroundCompetitions(), for use by
    // the background-competition tests below.
    UUID saveGameId;
    UUID managedClubId;
    Competition catarinense;
    Competition serieA;

    @BeforeEach
    void setUpManagedAndBackgroundCompetitions() {
        User u = User.create("bg_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        saveGameId = s.getId();

        Club managedClub = Club.create(saveGameId, "Managed Club", "MC");
        clubRepository.save(managedClub);
        managedClubId = managedClub.getId();

        // Catarinense: the managed club's phased competition (17 rounds, mirroring the real
        // data), so its window comfortably spans Serie A's 38 background rounds below.
        catarinense = makePhasedComp(saveGameId, "Catarinense", 2026, managedClubId).competition();
        // Serie A: a plain 20-club league the managed club does NOT belong to.
        serieA = makeComp(saveGameId, "SerieA", 2026, 20);
    }

    private Competition makeComp(UUID save, String name, int seasonYear, int clubCount) {
        return makeComp(save, name, seasonYear, clubCount, null);
    }

    /**
     * Same as the 4-arg overload, but when {@code includeClubId} is given it is seeded as the
     * competition's first member instead of a freshly created club -- letting a test place a
     * known club (e.g. the managed club) inside a competition without changing the total member
     * count that the round-count math ({@code 2 * (clubCount - 1)}) depends on.
     */
    private Competition makeComp(UUID save, String name, int seasonYear, int clubCount, UUID includeClubId) {
        Competition c = Competition.create(save, name, "LEAGUE", seasonYear, 2 * (clubCount - 1));
        competitionRepository.save(c);
        for (int i = 0; i < clubCount; i++) {
            UUID clubId;
            if (i == 0 && includeClubId != null) {
                clubId = includeClubId;
            } else {
                Club club = Club.create(save, name + " Club " + i, name.substring(0, 1) + i);
                clubRepository.save(club);
                clubId = club.getId();
            }
            competitionClubRepository.save(CompetitionClub.create(save, c.getId(), clubId));
        }
        return c;
    }

    /** Holds the competition plus the id of its first (round-robin) phase, needed by assertions. */
    private record PhasedCompetition(Competition competition, UUID firstPhaseId) { }

    /**
     * A 12-club competition seeded with the four Catarinense phases: Primeira Fase
     * (ROUND_ROBIN, 1 leg, 11 rounds, 8 qualifiers) then three KNOCKOUT phases
     * (Quartas de final / Semifinal / Final), each 2 legs / 2 rounds. totalRounds is
     * set to the phase-round sum (17), matching the switch SaveGameService makes.
     */
    private PhasedCompetition makePhasedComp(UUID save, String name, int seasonYear) {
        return makePhasedComp(save, name, seasonYear, null);
    }

    /** Same as the 3-arg overload, but seeds {@code includeClubId} as the first member when given. */
    private PhasedCompetition makePhasedComp(UUID save, String name, int seasonYear, UUID includeClubId) {
        Competition c = Competition.create(save, name, "LEAGUE", seasonYear, 17);
        competitionRepository.save(c);
        for (int i = 0; i < 12; i++) {
            UUID clubId;
            if (i == 0 && includeClubId != null) {
                clubId = includeClubId;
            } else {
                Club club = Club.create(save, name + " Club " + i, name.substring(0, 1) + i);
                clubRepository.save(club);
                clubId = club.getId();
            }
            competitionClubRepository.save(CompetitionClub.create(save, c.getId(), clubId));
        }
        CompetitionPhase firstPhase = competitionPhaseRepository.save(CompetitionPhase.create(
                save, c.getId(), 0, "Primeira Fase", PhaseKind.ROUND_ROBIN, 1, 11, 8));
        competitionPhaseRepository.save(CompetitionPhase.create(
                save, c.getId(), 1, "Quartas de final", PhaseKind.KNOCKOUT, 2, 2, null));
        competitionPhaseRepository.save(CompetitionPhase.create(
                save, c.getId(), 2, "Semifinal", PhaseKind.KNOCKOUT, 2, 2, null));
        competitionPhaseRepository.save(CompetitionPhase.create(
                save, c.getId(), 3, "Final", PhaseKind.KNOCKOUT, 2, 2, null));
        return new PhasedCompetition(c, firstPhase.getId());
    }

    @Test
    void generatesInterleavedDatedCalendarForTwoCompetitions() {
        User u = User.create("gen_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        Club managedClub = Club.create(s.getId(), "Managed Club", "MC");
        clubRepository.save(managedClub);
        // Both competitions include the managed club, so both stay on the interleaved cadence.
        Competition cat = makeComp(s.getId(), "Catarinense", 2026, 4, managedClub.getId()); // 4 clubs -> 6 rounds
        Competition serieB = makeComp(s.getId(), "SerieB", 2026, 6, managedClub.getId());   // 6 clubs -> 10 rounds

        generator.generate(s.getId(), 2026, List.of(cat, serieB), managedClub.getId());

        // Fixtures exist per competition, all dated, only over that competition's members.
        List<Fixture> catFx = fixtureRepository.findBySaveGameIdAndCompetitionId(s.getId(), cat.getId());
        List<Fixture> sbFx = fixtureRepository.findBySaveGameIdAndCompetitionId(s.getId(), serieB.getId());
        assertThat(catFx).allMatch(f -> f.getMatchDate() != null);
        assertThat(sbFx).allMatch(f -> f.getMatchDate() != null);
        assertThat(catFx).hasSize(4 * 3); // doubleRoundRobin(4) = 12 fixtures (6 rounds x 2)
        assertThat(sbFx).hasSize(6 * 5);  // doubleRoundRobin(6) = 30 fixtures (10 rounds x 2)

        // MATCH events: 6 + 10 = 16 match-units; first match-unit is at SEASON_START.
        List<SeasonEvent> events = seasonEventRepository.findBySaveGameId(s.getId());
        List<SeasonEvent> matches = events.stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .sorted((a, b) -> a.getEventDate().isEqual(b.getEventDate())
                        ? Integer.compare(a.getSequence(), b.getSequence())
                        : a.getEventDate().compareTo(b.getEventDate()))
                .toList();
        assertThat(matches).hasSize(16);
        assertThat(matches.get(0).getEventDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        // Interleave: first two match-units are different competitions on consecutive dates.
        assertThat(matches.get(0).getCompetitionId()).isNotEqualTo(matches.get(1).getCompetitionId());
        assertThat(matches.get(1).getEventDate()).isEqualTo(LocalDate.of(2026, 1, 20)); // +5d

        // TRANSFER events fall only inside window ranges (pre-season first 42 days, mid [+140,+168)).
        List<SeasonEvent> transfers = events.stream()
                .filter(e -> e.getType() == SeasonEventType.TRANSFER).toList();
        assertThat(transfers).isNotEmpty();
        assertThat(transfers).hasSize(8); // 5 pre-season (days 0,10,20,30,40) + 3 mid (days 140,150,160)
        LocalDate start = LocalDate.of(2026, 1, 15);
        assertThat(transfers).allMatch(t -> {
            boolean pre = !t.getEventDate().isBefore(start) && t.getEventDate().isBefore(start.plusDays(42));
            boolean mid = !t.getEventDate().isBefore(start.plusDays(140)) && t.getEventDate().isBefore(start.plusDays(168));
            return pre || mid;
        });
    }

    @Test
    void aPhasedCompetitionMaterializesTheRoundRobinAndReservesTheKnockout() {
        // Given: a 12-club competition with the four Catarinense phases
        User u = User.create("phased_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        PhasedCompetition pc = makePhasedComp(s.getId(), "Catarinense", 2026);
        UUID saveGameId = s.getId();
        UUID firstPhaseId = pc.firstPhaseId();
        UUID managedClubId = competitionClubRepository.findByCompetitionId(pc.competition().getId())
                .get(0).getClubId();

        // When
        generator.generate(saveGameId, 2026, List.of(pc.competition()), managedClubId);

        // Then: the first phase's 66 fixtures exist across rounds 0..10
        List<Fixture> fixtures = fixtureRepository.findBySaveGameId(saveGameId);
        assertThat(fixtures).hasSize(66);
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getRound()).isBetween(0, 10));
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isEqualTo(firstPhaseId));

        // And: rounds 11..16 are reserved as MATCH events carrying a phase but no fixtures
        List<SeasonEvent> events = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .sorted(Comparator.comparing(SeasonEvent::getEventDate)).toList();
        assertThat(events).hasSize(17);
        assertThat(events.subList(11, 17)).allSatisfy(e ->
                assertThat(e.getPhaseId()).isNotNull());
        assertThat(fixtures).noneSatisfy(f -> assertThat(f.getRound()).isGreaterThan(10));
    }

    @Test
    void matchDatesRemainFiveDaysApartAcrossAllSeventeenUnits() {
        User u = User.create("phased_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        PhasedCompetition pc = makePhasedComp(s.getId(), "Catarinense", 2026);
        UUID saveGameId = s.getId();
        UUID managedClubId = competitionClubRepository.findByCompetitionId(pc.competition().getId())
                .get(0).getClubId();

        generator.generate(saveGameId, 2026, List.of(pc.competition()), managedClubId);

        List<LocalDate> dates = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .map(SeasonEvent::getEventDate).sorted().toList();

        assertThat(dates).hasSize(17);
        assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(dates.get(16)).isEqualTo(LocalDate.of(2026, 1, 15).plusDays(16L * 5));
    }

    @Test
    void aCompetitionWithNoPhasesGeneratesExactlyAsBefore() {
        // Given a 20-club competition with no phase rows (Série B)
        User u = User.create("nophase_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        Competition serieB = makeComp(s.getId(), "SerieB", 2026, 20);
        UUID saveGameId = s.getId();
        UUID managedClubId = competitionClubRepository.findByCompetitionId(serieB.getId())
                .get(0).getClubId();

        generator.generate(saveGameId, 2026, List.of(serieB), managedClubId);

        List<Fixture> fixtures = fixtureRepository.findBySaveGameId(saveGameId);
        assertThat(fixtures).hasSize(380);          // 20 clubs, double round-robin
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isNull());
    }

    @Test
    void aBackgroundCompetitionDoesNotMoveAnyManagedFixture() {
        // Given: the same save generated with and without a background competition.
        // The managed competition's dates must be byte-identical -- adding a division the player
        // does not play in must not change their season by one day.
        generator.generate(saveGameId, 2026, List.of(catarinense), managedClubId);
        List<LocalDate> before = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId()).stream()
                .map(Fixture::getMatchDate).sorted().toList();

        fixtureRepository.deleteAll(fixtureRepository.findBySaveGameId(saveGameId));
        seasonEventRepository.deleteAll(seasonEventRepository.findBySaveGameId(saveGameId));

        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);
        List<LocalDate> after = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, catarinense.getId()).stream()
                .map(Fixture::getMatchDate).sorted().toList();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void aBackgroundCompetitionIsDatedAcrossTheSameSeasonWindow() {
        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        LocalDate lastManaged = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> catarinense.getId().equals(e.getCompetitionId()))
                .map(SeasonEvent::getEventDate).max(LocalDate::compareTo).orElseThrow();
        List<LocalDate> background = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> serieA.getId().equals(e.getCompetitionId()))
                .map(SeasonEvent::getEventDate).sorted().toList();

        // One event per round, all inside the managed season's window, strictly increasing.
        assertThat(background).hasSize(serieA.getTotalRounds());
        assertThat(background.get(0)).isAfterOrEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(background.get(background.size() - 1)).isBeforeOrEqualTo(lastManaged);
        assertThat(background).isSorted();
    }

    @Test
    void aBackgroundCompetitionMaterializesAllItsFixtures() {
        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        // A plain 20-club league: 380 fixtures, all dated, none phased.
        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, serieA.getId());
        assertThat(fixtures).hasSize(380);
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getMatchDate()).isNotNull());
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isNull());
    }

}
