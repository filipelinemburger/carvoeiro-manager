package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureScheduler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Builds a save's dated season calendar: per-competition fixtures over members, interleaved
 * across dates from mid-January, plus a season_event queue (MATCH per round + TRANSFER stops
 * inside the window ranges). Pure deterministic — LocalDate arithmetic + id-sorted iteration,
 * no RNG, no wall-clock. Used at career creation and rollover.
 */
@Service
public class SeasonCalendarGenerator {

    static final int MATCH_INTERVAL_DAYS = 5;
    static final int TRANSFER_INTERVAL_DAYS = 10;
    static final int PRE_SEASON_DAYS = 42;
    static final int MID_WINDOW_START_DAYS = 140;
    static final int MID_WINDOW_END_DAYS = 168;
    static final int PRE_CONTRACT_WINDOW_START_DAYS = 181; // July 15 = Jan 15 + 181 days

    private final CompetitionClubRepository competitionClubRepository;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    // Fixtures and season events are inserted via persist() (not repository.save()) so the
    // assigned-UUID ids don't trigger a merge() SELECT-before-INSERT on every one of the
    // hundreds of rows a season calendar produces. See SaveGameService for the same rationale.
    @PersistenceContext
    private EntityManager entityManager;

    public SeasonCalendarGenerator(CompetitionClubRepository competitionClubRepository) {
        this.competitionClubRepository = competitionClubRepository;
    }

    /** A scheduled match-unit: one competition's round, to be dated and persisted. */
    private record MatchUnit(UUID competitionId, int round, List<Fixture> fixtures) { }

    // Persists via the EntityManager, so it needs an active transaction. In production it joins
    // createCareer()/rollover's existing transaction (REQUIRED); called standalone it opens its own.
    @Transactional
    public void generate(UUID saveGameId, int seasonYear, List<Competition> competitions) {
        final LocalDate seasonStart = LocalDate.of(seasonYear, 1, 15);

        List<Competition> comps = competitions.stream()
                .sorted(Comparator.comparing(Competition::getId)).toList();

        // 1. Build each competition's rounds (fixtures not yet dated/persisted), grouped by round.
        List<List<MatchUnit>> roundsByComp = new ArrayList<>();
        int maxRounds = 0;
        for (Competition comp : comps) {
            List<UUID> memberIds = competitionClubRepository.findByCompetitionId(comp.getId()).stream()
                    .map(cc -> cc.getClubId()).sorted().toList();
            int count = memberIds.size();
            // round -> its fixtures
            List<List<Fixture>> byRound = new ArrayList<>();
            for (int r = 0; r < comp.getTotalRounds(); r++) {
                byRound.add(new ArrayList<>());
            }
            fixtureScheduler.doubleRoundRobin(count).forEach(scheduled -> {
                UUID home = memberIds.get(scheduled.homeIndex());
                UUID away = memberIds.get(scheduled.awayIndex());
                // matchDate filled when the match-unit is dated below; create with null placeholder then set.
                Fixture fx = Fixture.create(saveGameId, comp.getId(), scheduled.round(), home, away, null);
                byRound.get(scheduled.round()).add(fx);
            });
            List<MatchUnit> units = new ArrayList<>();
            for (int r = 0; r < byRound.size(); r++) {
                units.add(new MatchUnit(comp.getId(), r, byRound.get(r)));
            }
            roundsByComp.add(units);
            maxRounds = Math.max(maxRounds, units.size());
        }

        // 2. Interleave: round index ascending; within a round, competitions in id order.
        List<MatchUnit> ordered = new ArrayList<>();
        for (int r = 0; r < maxRounds; r++) {
            for (List<MatchUnit> units : roundsByComp) {
                if (r < units.size()) {
                    ordered.add(units.get(r));
                }
            }
        }

        // 3. Date + persist each match-unit's fixtures + a MATCH event (sequence 0).
        for (int i = 0; i < ordered.size(); i++) {
            MatchUnit unit = ordered.get(i);
            LocalDate date = seasonStart.plusDays((long) i * MATCH_INTERVAL_DAYS);
            for (Fixture fx : unit.fixtures()) {
                fx.setMatchDate(date);
                entityManager.persist(fx);
            }
            entityManager.persist(
                    SeasonEvent.createMatch(saveGameId, date, unit.competitionId(), unit.round(), 0));
        }

        // 4. TRANSFER events inside each window range (sequence 1), plus pre-contract
        //    market stops from July 15 up to the final scheduled match. Stop offsets are
        //    ≡ 1 (mod 5) so they never share a date with a MATCH event (multiples of 5)
        //    and always precede the season finale that flips the save to FINISHED.
        addTransferEvents(saveGameId, seasonStart, 0, PRE_SEASON_DAYS);
        addTransferEvents(saveGameId, seasonStart, MID_WINDOW_START_DAYS, MID_WINDOW_END_DAYS);
        int lastMatchOffset = (ordered.size() - 1) * MATCH_INTERVAL_DAYS;
        addTransferEvents(saveGameId, seasonStart, PRE_CONTRACT_WINDOW_START_DAYS, lastMatchOffset + 1);
    }

    private void addTransferEvents(UUID saveGameId, LocalDate seasonStart, int startOffset, int endOffset) {
        for (int d = startOffset; d < endOffset; d += TRANSFER_INTERVAL_DAYS) {
            entityManager.persist(
                    SeasonEvent.createTransfer(saveGameId, seasonStart.plusDays(d), 1));
        }
    }
}
