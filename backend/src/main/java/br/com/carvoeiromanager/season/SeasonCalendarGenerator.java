package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureScheduler;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.competition.ScheduledFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds a save's dated season calendar: per-competition fixtures over members, dated from
 * mid-January, plus a season_event queue (MATCH per round + TRANSFER stops inside the window
 * ranges). Competitions the managed club plays in are interleaved onto one shared cadence;
 * every other competition is spread proportionally across the same window without consuming a
 * slot. Pure deterministic — LocalDate arithmetic + id-sorted iteration, no RNG, no wall-clock.
 * Used at career creation and rollover.
 */
@Service
public class SeasonCalendarGenerator {

    static final int MATCH_INTERVAL_DAYS = 5;
    static final int TRANSFER_INTERVAL_DAYS = 10;
    static final int PRE_SEASON_DAYS = 42;
    static final int MID_WINDOW_START_DAYS = 140;
    static final int MID_WINDOW_END_DAYS = 168;
    static final int PRE_CONTRACT_WINDOW_START_DAYS = 181; // July 15 = Jan 15 + 181 days

    // season_event rows are popped in (event_date, sequence) order, so the sequence is the only
    // tiebreak between events that share a date. The three values below make that order total:
    // the player's own match first, then the day's transfer stop, then the background world —
    // which the player never waits on. Without a distinct background sequence, a background round
    // sharing a date with a managed one would be returned in DB-arbitrary order and callers such
    // as MatchPreviewService.maybePersistPreview would flip non-deterministically.
    static final int MANAGED_MATCH_SEQUENCE = 0;
    static final int TRANSFER_SEQUENCE = 1;
    static final int BACKGROUND_MATCH_SEQUENCE = 2;

    private final CompetitionClubRepository competitionClubRepository;
    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    // Fixtures and season events are inserted via persist() (not repository.save()) so the
    // assigned-UUID ids don't trigger a merge() SELECT-before-INSERT on every one of the
    // hundreds of rows a season calendar produces. See SaveGameService for the same rationale.
    @PersistenceContext
    private EntityManager entityManager;

    public SeasonCalendarGenerator(CompetitionClubRepository competitionClubRepository,
                                   CompetitionPhaseRepository competitionPhaseRepository) {
        this.competitionClubRepository = competitionClubRepository;
        this.competitionPhaseRepository = competitionPhaseRepository;
    }

    /** A scheduled match-unit: one competition's round, to be dated and persisted. */
    private record MatchUnit(UUID competitionId, UUID phaseId, int round, List<Fixture> fixtures) { }

    /**
     * Builds and persists the whole dated calendar for one save's season.
     *
     * <p>Persists via the EntityManager, so it needs an active transaction. In production it joins
     * createCareer()/rollover's existing transaction (REQUIRED); called standalone it opens its own.
     *
     * @param saveGameId    the save the fixtures and season events belong to
     * @param seasonYear    the calendar year the season opens in; the window always starts Jan 15
     * @param competitions  every competition in the save, managed and background alike
     * @param managedClubId the club the player manages. Competitions it belongs to are interleaved
     *                      onto one shared 5-day cadence (their matches must not collide); every
     *                      other competition is dated independently across the same window, so
     *                      adding a division the player does not play in never lengthens their
     *                      season. Passed in rather than read from the save because
     *                      SaveGameService generates the calendar BEFORE it sets the managed club.
     */
    @Transactional
    public void generate(UUID saveGameId, int seasonYear, List<Competition> competitions,
                         UUID managedClubId) {
        // Without a managed club every competition would look like background: the player would get
        // a career with zero matches of their own and no way to notice until the save never advances.
        Objects.requireNonNull(managedClubId,
                "managedClubId is required to generate a season calendar — the caller must pass the "
                        + "club the player manages, not the save's (still unset) managedClubId");

        final LocalDate seasonStart = LocalDate.of(seasonYear, 1, 15);

        final List<Competition> comps = competitions.stream()
                .sorted(Comparator.comparing(Competition::getId)).toList();

        // Partition by membership (never by a stored flag), capturing each competition's member ids
        // once — both the partition test and the fixture building below need them.
        final List<Competition> managed = new ArrayList<>();
        final List<Competition> background = new ArrayList<>();
        final Map<UUID, List<UUID>> memberIdsByCompetition = new HashMap<>();
        for (Competition comp : comps) {
            final List<UUID> memberIds = competitionClubRepository.findByCompetitionId(comp.getId())
                    .stream().map(cc -> cc.getClubId()).sorted().toList();
            memberIdsByCompetition.put(comp.getId(), memberIds);
            (memberIds.contains(managedClubId) ? managed : background).add(comp);
        }
        if (!comps.isEmpty() && managed.isEmpty()) {
            throw new IllegalStateException(
                    "Managed club " + managedClubId + " has no competition_club row in any of the "
                            + comps.size() + " competitions being generated — the calendar would "
                            + "contain no matches for the player at all");
        }

        // 1. Build each managed competition's rounds (fixtures not yet dated/persisted), grouped
        //    by round. Background competitions are dated independently below (step 4).
        final List<List<MatchUnit>> roundsByComp = new ArrayList<>();
        int maxRounds = 0;
        for (Competition comp : managed) {
            final List<MatchUnit> units =
                    buildUnits(saveGameId, comp, memberIdsByCompetition.get(comp.getId()));
            roundsByComp.add(units);
            maxRounds = Math.max(maxRounds, units.size());
        }

        // 2. Interleave: round index ascending; within a round, competitions in id order.
        final List<MatchUnit> ordered = new ArrayList<>();
        for (int r = 0; r < maxRounds; r++) {
            for (List<MatchUnit> units : roundsByComp) {
                if (r < units.size()) {
                    ordered.add(units.get(r));
                }
            }
        }

        // 3. Date + persist each match-unit's fixtures + a MATCH event.
        for (int i = 0; i < ordered.size(); i++) {
            persistUnit(saveGameId, ordered.get(i),
                    seasonStart.plusDays((long) i * MATCH_INTERVAL_DAYS), MANAGED_MATCH_SEQUENCE);
        }

        // The managed season spans from the opening day to the final match-unit; that same offset is
        // both the width of the window background competitions spread across and the last date the
        // pre-contract market may open on.
        final int lastManagedMatchOffset =
                ordered.isEmpty() ? 0 : (ordered.size() - 1) * MATCH_INTERVAL_DAYS;

        // Background competitions run in parallel across the same window the managed season
        // occupies, so their tables advance at a believable pace without consuming any of the
        // player's match slots.
        for (Competition comp : background) {
            generateBackground(saveGameId, seasonStart, comp,
                    memberIdsByCompetition.get(comp.getId()), lastManagedMatchOffset);
        }

        // 4. TRANSFER events inside each window range, plus pre-contract market stops from July 15
        //    up to the final scheduled match. Stop offsets are ≡ 1 (mod 5) so they never share a
        //    date with a managed MATCH event (multiples of 5) and always precede the season finale
        //    that flips the save to FINISHED.
        addTransferEvents(saveGameId, seasonStart, 0, PRE_SEASON_DAYS);
        addTransferEvents(saveGameId, seasonStart, MID_WINDOW_START_DAYS, MID_WINDOW_END_DAYS);
        addTransferEvents(saveGameId, seasonStart, PRE_CONTRACT_WINDOW_START_DAYS,
                lastManagedMatchOffset + 1);
    }

    /** One competition's undated match-units: phase-aware when it has phases, legacy otherwise. */
    private List<MatchUnit> buildUnits(UUID saveGameId, Competition comp, List<UUID> memberIds) {
        final List<CompetitionPhase> phases =
                competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(comp.getId());
        return phases.isEmpty()
                ? legacyLeagueUnits(saveGameId, comp, memberIds)
                : phasedUnits(saveGameId, comp, memberIds, phases);
    }

    /** Dates a match-unit's fixtures and persists them alongside the unit's MATCH event. */
    private void persistUnit(UUID saveGameId, MatchUnit unit, LocalDate date, int sequence) {
        for (Fixture fx : unit.fixtures()) {
            fx.setMatchDate(date);
            entityManager.persist(fx);
        }
        entityManager.persist(unit.phaseId() == null
                ? SeasonEvent.createMatch(saveGameId, date, unit.competitionId(), unit.round(), sequence)
                : SeasonEvent.createPhased(saveGameId, date, unit.competitionId(),
                        unit.phaseId(), unit.round(), sequence));
    }

    /** Pre-V47 behaviour: one double round-robin over all members, no phases. */
    private List<MatchUnit> legacyLeagueUnits(UUID saveGameId, Competition comp, List<UUID> memberIds) {
        List<List<Fixture>> byRound = new ArrayList<>();
        for (int r = 0; r < comp.getTotalRounds(); r++) {
            byRound.add(new ArrayList<>());
        }
        fixtureScheduler.doubleRoundRobin(memberIds.size()).forEach(scheduled -> {
            UUID home = memberIds.get(scheduled.homeIndex());
            UUID away = memberIds.get(scheduled.awayIndex());
            byRound.get(scheduled.round()).add(
                    Fixture.create(saveGameId, comp.getId(), scheduled.round(), home, away, null));
        });
        List<MatchUnit> units = new ArrayList<>();
        for (int r = 0; r < byRound.size(); r++) {
            units.add(new MatchUnit(comp.getId(), null, r, byRound.get(r)));
        }
        return units;
    }

    /**
     * Phase-aware units. A ROUND_ROBIN phase materializes its fixtures now; a KNOCKOUT phase
     * only reserves its match-units — participants are unknown until the previous phase ends,
     * and fixtures.home_club_id is NOT NULL so placeholders are impossible. PhaseResolver fills
     * these in during the season.
     */
    private List<MatchUnit> phasedUnits(UUID saveGameId, Competition comp, List<UUID> memberIds,
                                        List<CompetitionPhase> phases) {
        List<MatchUnit> units = new ArrayList<>();
        int roundOffset = 0;
        for (CompetitionPhase phase : phases) {
            if (phase.getKind() == PhaseKind.ROUND_ROBIN) {
                List<List<Fixture>> byRound = new ArrayList<>();
                for (int r = 0; r < phase.getRounds(); r++) {
                    byRound.add(new ArrayList<>());
                }
                List<ScheduledFixture> scheduled = phase.getLegs() == 1
                        ? fixtureScheduler.singleRoundRobin(memberIds.size())
                        : fixtureScheduler.doubleRoundRobin(memberIds.size());
                final int offset = roundOffset;
                scheduled.forEach(s -> {
                    UUID home = memberIds.get(s.homeIndex());
                    UUID away = memberIds.get(s.awayIndex());
                    Fixture fx = Fixture.create(saveGameId, comp.getId(),
                            offset + s.round(), home, away, null);
                    fx.setPhaseId(phase.getId());
                    byRound.get(s.round()).add(fx);
                });
                for (int r = 0; r < byRound.size(); r++) {
                    units.add(new MatchUnit(comp.getId(), phase.getId(), roundOffset + r, byRound.get(r)));
                }
            } else {
                // Reserved: dated and ordered, but no fixtures yet.
                for (int r = 0; r < phase.getRounds(); r++) {
                    units.add(new MatchUnit(comp.getId(), phase.getId(), roundOffset + r, List.of()));
                }
            }
            roundOffset += phase.getRounds();
        }
        return units;
    }

    /**
     * Dates one background competition's match-units proportionally across the managed season's
     * window and persists its fixtures and MATCH events. The units come from the same builder the
     * managed side uses, so a phased background competition (a state championship the managed club
     * is not in) generates exactly like a managed one — only the dating differs. The events exist
     * so BackgroundCompetitionService can tell what is due; they are distinguished from managed
     * ones by their competition's membership, never by a column.
     *
     * <p><b>Two background units may share a date</b> when the competition has more units than the
     * window has days. That is intentional — background rounds resolve silently, so nothing waits
     * on them — but it means the resolver must order due rounds by {@code (event_date, round)}, not
     * by date alone, or a later round could be simulated before the one before it.
     */
    private void generateBackground(UUID saveGameId, LocalDate seasonStart, Competition comp,
                                    List<UUID> memberIds, int managedSeasonDays) {
        final List<MatchUnit> units = buildUnits(saveGameId, comp, memberIds);
        if (units.isEmpty()) {
            return;
        }
        // Proportional distribution over [seasonStart, seasonStart + managedSeasonDays]: unit 0
        // opens on the season's first day and the LAST unit lands exactly on the managed finale.
        // Dividing by (units - 1) rather than by the unit count is what makes that last unit reach
        // the end of the window instead of stopping one interval short; clamping a fixed interval
        // at 1 day (the previous approach) instead ran long competitions PAST the finale, leaving
        // their tables permanently unfinished because the save flips to FINISHED first.
        // The offset is round(r * managedSeasonDays / lastUnit) computed in exact long arithmetic
        // — (2ab + m) / 2m is floor(a*b/m + 1/2) for non-negative operands — so the calendar stays
        // bit-for-bit reproducible with no floating point anywhere.
        final long lastUnit = units.size() - 1L;
        for (int r = 0; r < units.size(); r++) {
            final long offset = lastUnit == 0L
                    ? 0L
                    : (2L * r * managedSeasonDays + lastUnit) / (2L * lastUnit);
            persistUnit(saveGameId, units.get(r), seasonStart.plusDays(offset),
                    BACKGROUND_MATCH_SEQUENCE);
        }
    }

    private void addTransferEvents(UUID saveGameId, LocalDate seasonStart, int startOffset, int endOffset) {
        for (int d = startOffset; d < endOffset; d += TRANSFER_INTERVAL_DAYS) {
            entityManager.persist(
                    SeasonEvent.createTransfer(saveGameId, seasonStart.plusDays(d), TRANSFER_SEQUENCE));
        }
    }
}
