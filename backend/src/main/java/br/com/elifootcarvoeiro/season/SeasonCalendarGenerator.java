package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.competition.Competition;
import br.com.elifootcarvoeiro.competition.CompetitionClubRepository;
import br.com.elifootcarvoeiro.competition.Fixture;
import br.com.elifootcarvoeiro.competition.FixtureRepository;
import br.com.elifootcarvoeiro.competition.FixtureScheduler;
import org.springframework.stereotype.Service;

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

    private final CompetitionClubRepository competitionClubRepository;
    private final FixtureRepository fixtureRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public SeasonCalendarGenerator(CompetitionClubRepository competitionClubRepository,
                                   FixtureRepository fixtureRepository,
                                   SeasonEventRepository seasonEventRepository) {
        this.competitionClubRepository = competitionClubRepository;
        this.fixtureRepository = fixtureRepository;
        this.seasonEventRepository = seasonEventRepository;
    }

    /** A scheduled match-unit: one competition's round, to be dated and persisted. */
    private record MatchUnit(UUID competitionId, int round, List<Fixture> fixtures) { }

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
                fixtureRepository.save(fx);
            }
            seasonEventRepository.save(
                    SeasonEvent.createMatch(saveGameId, date, unit.competitionId(), unit.round(), 0));
        }

        // 4. TRANSFER events inside each window range (sequence 1).
        addTransferEvents(saveGameId, seasonStart, 0, PRE_SEASON_DAYS);
        addTransferEvents(saveGameId, seasonStart, MID_WINDOW_START_DAYS, MID_WINDOW_END_DAYS);
    }

    private void addTransferEvents(UUID saveGameId, LocalDate seasonStart, int startOffset, int endOffset) {
        for (int d = startOffset; d < endOffset; d += TRANSFER_INTERVAL_DAYS) {
            seasonEventRepository.save(
                    SeasonEvent.createTransfer(saveGameId, seasonStart.plusDays(d), 1));
        }
    }
}
