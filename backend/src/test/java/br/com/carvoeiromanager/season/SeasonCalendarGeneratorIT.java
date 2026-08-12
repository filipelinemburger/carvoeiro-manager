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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        UUID saveId = s.getId();
        UUID firstPhaseId = pc.firstPhaseId();
        UUID clubInComp = competitionClubRepository.findByCompetitionId(pc.competition().getId())
                .get(0).getClubId();

        // When
        generator.generate(saveId, 2026, List.of(pc.competition()), clubInComp);

        // Then: the first phase's 66 fixtures exist across rounds 0..10
        List<Fixture> fixtures = fixtureRepository.findBySaveGameId(saveId);
        assertThat(fixtures).hasSize(66);
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getRound()).isBetween(0, 10));
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isEqualTo(firstPhaseId));

        // And: rounds 11..16 are reserved as MATCH events carrying a phase but no fixtures
        List<SeasonEvent> events = seasonEventRepository.findBySaveGameId(saveId).stream()
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
        UUID saveId = s.getId();
        UUID clubInComp = competitionClubRepository.findByCompetitionId(pc.competition().getId())
                .get(0).getClubId();

        generator.generate(saveId, 2026, List.of(pc.competition()), clubInComp);

        List<LocalDate> dates = seasonEventRepository.findBySaveGameId(saveId).stream()
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
        UUID saveId = s.getId();
        UUID clubInComp = competitionClubRepository.findByCompetitionId(serieB.getId())
                .get(0).getClubId();

        generator.generate(saveId, 2026, List.of(serieB), clubInComp);

        List<Fixture> fixtures = fixtureRepository.findBySaveGameId(saveId);
        assertThat(fixtures).hasSize(380);          // 20 clubs, double round-robin
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getPhaseId()).isNull());
    }

    /**
     * One fixture's schedule slot. Comparing (round, date) pairs -- not just the date multiset --
     * is what makes a regression that permutes round-to-date while preserving the set of dates fail.
     */
    private record FixtureSlot(int round, LocalDate matchDate) { }

    /** The identifying columns of a season event, for a before/after comparison of the queue. */
    private record EventSlot(LocalDate eventDate, SeasonEventType type, UUID competitionId,
                             Integer round, int sequence) { }

    private static final Comparator<FixtureSlot> BY_ROUND_THEN_DATE =
            Comparator.comparingInt(FixtureSlot::round).thenComparing(FixtureSlot::matchDate);

    private static final Comparator<EventSlot> BY_EVENT_IDENTITY =
            Comparator.comparing(EventSlot::eventDate)
                    .thenComparing(EventSlot::type)
                    .thenComparing(EventSlot::competitionId,
                            Comparator.nullsFirst(Comparator.<UUID>naturalOrder()))
                    .thenComparing(EventSlot::round,
                            Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
                    .thenComparingInt(EventSlot::sequence);

    /** Every (round, date) slot of the given competition's fixtures in this save, sorted. */
    private List<FixtureSlot> fixtureSlots(Competition competition) {
        return fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competition.getId())
                .stream()
                .map(f -> new FixtureSlot(f.getRound(), f.getMatchDate()))
                .sorted(BY_ROUND_THEN_DATE)
                .toList();
    }

    /**
     * This save's season events restricted to the given competition plus every TRANSFER stop --
     * the latter because the pre-contract window's end derives from the managed season's last
     * match offset, so a change to that offset must show up here.
     */
    private List<EventSlot> eventSlotsFor(Competition competition) {
        return seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.TRANSFER
                        || competition.getId().equals(e.getCompetitionId()))
                .map(e -> new EventSlot(e.getEventDate(), e.getType(), e.getCompetitionId(),
                        e.getRound(), e.getSequence()))
                .sorted(BY_EVENT_IDENTITY)
                .toList();
    }

    private void clearGeneratedCalendar() {
        fixtureRepository.deleteAll(fixtureRepository.findBySaveGameId(saveGameId));
        seasonEventRepository.deleteAll(seasonEventRepository.findBySaveGameId(saveGameId));
    }

    /** The last date any MATCH event of the given competition is scheduled on in this save. */
    private LocalDate lastMatchDateOf(Competition competition) {
        return seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> competition.getId().equals(e.getCompetitionId()))
                .map(SeasonEvent::getEventDate).max(LocalDate::compareTo).orElseThrow();
    }

    private List<LocalDate> matchDatesOf(Competition competition) {
        return seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> e.getType() == SeasonEventType.MATCH)
                .filter(e -> competition.getId().equals(e.getCompetitionId()))
                .map(SeasonEvent::getEventDate).sorted().toList();
    }

    @Test
    void aBackgroundCompetitionDoesNotMoveAnyManagedFixture() {
        // Given: the same save generated with and without a background competition. Adding a
        // division the player does not play in must not change their season by one day -- so both
        // the (round, date) slots of every managed fixture AND the managed slice of the event
        // queue (including the TRANSFER stops, whose final window derives from the last managed
        // match) must come back identical.
        generator.generate(saveGameId, 2026, List.of(catarinense), managedClubId);
        List<FixtureSlot> fixturesBefore = fixtureSlots(catarinense);
        List<EventSlot> eventsBefore = eventSlotsFor(catarinense);

        clearGeneratedCalendar();

        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        assertThat(fixtureSlots(catarinense)).isEqualTo(fixturesBefore);
        assertThat(eventSlotsFor(catarinense)).isEqualTo(eventsBefore);
    }

    @Test
    void aBackgroundCompetitionIsDatedAcrossTheSameSeasonWindow() {
        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        LocalDate lastManaged = lastMatchDateOf(catarinense);
        List<LocalDate> background = matchDatesOf(serieA);

        // One event per round, opening with the managed season and never running past its finale.
        // isSorted() only proves non-decreasing -- two background rounds are allowed to share a
        // date when the window is tighter than the round count.
        assertThat(background).hasSize(serieA.getTotalRounds());
        assertThat(background.get(0)).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(background.get(background.size() - 1)).isBeforeOrEqualTo(lastManaged);
        assertThat(background).isSorted();
    }

    @Test
    void theLastBackgroundRoundLandsExactlyOnTheManagedSeasonFinale() {
        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        List<LocalDate> background = matchDatesOf(serieA);

        // Proportional dating spreads the rounds over the whole window, so the final background
        // round is due on the same day as the managed finale -- not one interval short of it,
        // which would leave the table unfinished when the save flips to FINISHED.
        assertThat(background.get(background.size() - 1)).isEqualTo(lastMatchDateOf(catarinense));
    }

    @Test
    void aBackgroundCompetitionWithMoreRoundsThanWindowDaysStillFinishesInsideTheWindow() {
        // Given: a tiny managed competition (4 clubs -> 6 units -> a 25-day window) alongside a
        // 20-club background league whose 38 rounds outnumber the window's days.
        Competition tinyManaged = makeComp(saveGameId, "TinyManaged", 2026, 4, managedClubId);

        generator.generate(saveGameId, 2026, List.of(tinyManaged, serieA), managedClubId);

        LocalDate lastManaged = lastMatchDateOf(tinyManaged);
        List<LocalDate> background = matchDatesOf(serieA);

        assertThat(background).hasSize(serieA.getTotalRounds());
        assertThat(background).allSatisfy(d -> assertThat(d).isBeforeOrEqualTo(lastManaged));
        assertThat(background.get(background.size() - 1)).isEqualTo(lastManaged);
        // Rounds necessarily share dates here; that is intentional, which is why the resolver
        // must order due background rounds by (date, round) and not by date alone.
        assertThat(background.stream().distinct().count()).isLessThan(background.size());
    }

    @Test
    void twoBackgroundCompetitionsAreBothDatedAndNeitherMovesTheManagedSeason() {
        Competition serieB = makeComp(saveGameId, "SerieB", 2026, 8); // excludes the managed club

        generator.generate(saveGameId, 2026, List.of(catarinense), managedClubId);
        List<FixtureSlot> fixturesBefore = fixtureSlots(catarinense);
        List<EventSlot> eventsBefore = eventSlotsFor(catarinense);

        clearGeneratedCalendar();

        generator.generate(saveGameId, 2026, List.of(catarinense, serieA, serieB), managedClubId);

        LocalDate lastManaged = lastMatchDateOf(catarinense);
        for (Competition bg : List.of(serieA, serieB)) {
            List<Fixture> fixtures = fixtureRepository
                    .findBySaveGameIdAndCompetitionId(saveGameId, bg.getId());
            assertThat(fixtures).isNotEmpty();
            assertThat(fixtures).allSatisfy(f -> assertThat(f.getMatchDate()).isNotNull());

            List<LocalDate> dates = matchDatesOf(bg);
            assertThat(dates).hasSize(bg.getTotalRounds());
            assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(dates.get(dates.size() - 1)).isEqualTo(lastManaged);
        }
        assertThat(fixtureSlots(catarinense)).isEqualTo(fixturesBefore);
        assertThat(eventSlotsFor(catarinense)).isEqualTo(eventsBefore);
    }

    @Test
    void aPhasedBackgroundCompetitionGeneratesThroughTheSamePhaseAwarePath() {
        // A 12-club two-phase state championship the managed club is NOT in: it must build its
        // units through the phase-aware builder, not a bare double round-robin sized to
        // totalRounds -- which used to blow up with IndexOutOfBoundsException.
        Competition paranaense = makePhasedComp(saveGameId, "Paranaense", 2026).competition();

        generator.generate(saveGameId, 2026, List.of(catarinense, paranaense), managedClubId);

        // The round-robin phase materialized (66 fixtures over rounds 0..10) and all 17 units are
        // dated, the knockout ones reserved without fixtures.
        List<Fixture> fixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(saveGameId, paranaense.getId());
        assertThat(fixtures).hasSize(66);
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getMatchDate()).isNotNull());
        assertThat(fixtures).allSatisfy(f -> assertThat(f.getRound()).isBetween(0, 10));

        List<LocalDate> dates = matchDatesOf(paranaense);
        assertThat(dates).hasSize(17);
        assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, 1, 15));
        assertThat(dates.get(16)).isEqualTo(lastMatchDateOf(catarinense));
    }

    @Test
    void backgroundMatchEventsSortAfterTheManagedOnesOnASharedDate() {
        generator.generate(saveGameId, 2026, List.of(catarinense, serieA), managedClubId);

        List<SeasonEvent> events = seasonEventRepository.findBySaveGameId(saveGameId);
        assertThat(events).filteredOn(e -> catarinense.getId().equals(e.getCompetitionId()))
                .allSatisfy(e -> assertThat(e.getSequence()).isZero());
        assertThat(events).filteredOn(e -> serieA.getId().equals(e.getCompetitionId()))
                .isNotEmpty()
                .allSatisfy(e -> assertThat(e.getSequence()).isEqualTo(2));
        assertThat(events).filteredOn(e -> e.getType() == SeasonEventType.TRANSFER)
                .allSatisfy(e -> assertThat(e.getSequence()).isEqualTo(1));
    }

    @Test
    void generatingWithoutAManagedClubIsRejected() {
        assertThatThrownBy(() ->
                generator.generate(saveGameId, 2026, List.of(catarinense, serieA), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("managedClubId");
    }

    @Test
    void generatingWhenTheManagedClubPlaysInNoCompetitionIsRejected() {
        // Every competition would be background: a career with zero matches of the player's own.
        assertThatThrownBy(() ->
                generator.generate(saveGameId, 2026, List.of(serieA), managedClubId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(managedClubId.toString());
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
