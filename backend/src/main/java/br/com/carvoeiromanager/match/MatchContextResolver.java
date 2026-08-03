package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Resolves the season-context inputs {@link br.com.carvoeiromanager.finance.AttendanceCalculator}
 * needs — a club's recent form and its league position/club count, each AS THEY STOOD strictly
 * BEFORE the fixture being resolved — shared by the two places a fixture gets finalised:
 * {@link FixtureSimulator} (AI-simulated rounds) and {@link LiveMatchService} (the managed club's
 * live match). Extracted so both call sites compute attendance identically rather than
 * maintaining two copies of this lookup logic.
 *
 * <p>Every fixture in one round of one competition shares the same {@code matchDate} (see
 * {@code SeasonCalendarGenerator}), so {@link #forRound} loads the competition's fixture list and
 * computes its as-of-{@code matchDate} standings table exactly ONCE per round, returning a
 * {@link RoundContext} whose per-fixture lookups are then pure in-memory work — instead of the ~9
 * queries and 3 full-season scans PER FIXTURE the naive per-call version cost. The as-of bound is
 * not just a performance nicety: without it, {@code StandingsService}'s derived query would
 * auto-flush and see the very match's own just-recorded result (a home side that wins 5-0 climbing
 * the table before its own crowd is priced), and the two call sites would disagree with each other
 * depending on how many of the round's other results were already flushed — {@code matchDate}
 * fixes both by construction.
 */
@Component
public class MatchContextResolver {

    /** Recent-form window: the club's last N played fixtures in the competition. */
    private static final int FORM_LOOKBACK = 5;

    /** Recent-form cap, matching {@code AttendanceCalculator}'s 0-15 scale. */
    private static final int FORM_MAX_POINTS = 15;

    private final FixtureRepository fixtureRepository;
    private final StandingsService standingsService;

    public MatchContextResolver(FixtureRepository fixtureRepository, StandingsService standingsService) {
        this.fixtureRepository = fixtureRepository;
        this.standingsService = standingsService;
    }

    /**
     * Builds the shared context for resolving every fixture of ONE round of {@code competitionId}
     * dated {@code matchDate}: the competition's full fixture list (used for recent form) and its
     * standings table as of strictly before {@code matchDate} (used for league position/club
     * count), each loaded/computed exactly once. A single fixture (e.g. the managed club's live
     * match) is simply the degenerate one-fixture case and goes through the same method.
     */
    public RoundContext forRound(SaveGame save, UUID competitionId, LocalDate matchDate) {
        List<Fixture> competitionFixtures =
                fixtureRepository.findBySaveGameIdAndCompetitionId(save.getId(), competitionId);
        List<StandingRow> standingsAsOf = standingsService.computeStandings(save, competitionId, matchDate);
        return new RoundContext(competitionFixtures, standingsAsOf);
    }

    private static int pointsFor(Fixture fixture, UUID clubId) {
        boolean isHome = fixture.getHomeClubId().equals(clubId);
        int clubGoals = isHome ? fixture.getHomeGoals() : fixture.getAwayGoals();
        int opponentGoals = isHome ? fixture.getAwayGoals() : fixture.getHomeGoals();
        if (clubGoals > opponentGoals) return 3;
        if (clubGoals == opponentGoals) return 1;
        return 0;
    }

    /**
     * One round's shared lookups (a competition's fixture list, plus its as-of-matchDate standings
     * table), each computed once by {@link #forRound} and queried per fixture here at zero extra
     * DB cost.
     */
    public static final class RoundContext {
        private final List<Fixture> competitionFixtures;
        private final List<StandingRow> standingsAsOf;

        private RoundContext(List<Fixture> competitionFixtures, List<StandingRow> standingsAsOf) {
            this.competitionFixtures = competitionFixtures;
            this.standingsAsOf = standingsAsOf;
        }

        /**
         * {@code clubId}'s points (3 win / 1 draw / 0 loss) from its last {@value #FORM_LOOKBACK}
         * PLAYED fixtures strictly before {@code matchDate}, capped at {@value #FORM_MAX_POINTS}.
         * The strict "before" bound excludes the fixture currently being resolved (and any other
         * same-round fixture sharing its date) even though it may already be marked played by the
         * time this runs. Fewer than 5 played fixtures simply uses what exists (early season).
         */
        public int recentFormPoints(UUID clubId, LocalDate matchDate) {
            List<Fixture> recent = competitionFixtures.stream()
                    .filter(Fixture::isPlayed)
                    .filter(f -> f.getHomeClubId().equals(clubId) || f.getAwayClubId().equals(clubId))
                    .filter(f -> f.getMatchDate() != null && matchDate != null && f.getMatchDate().isBefore(matchDate))
                    .sorted(Comparator.comparing(Fixture::getMatchDate).thenComparing(Fixture::getRound).reversed())
                    .limit(FORM_LOOKBACK)
                    .toList();

            int points = recent.stream().mapToInt(f -> pointsFor(f, clubId)).sum();
            return Math.min(FORM_MAX_POINTS, points);
        }

        /**
         * {@code clubId}'s 1-based position in the as-of-matchDate standings table — NEUTRAL
         * (mid-table) if no fixture in the competition has been played as of {@code matchDate} yet.
         * Without that guard, matchday 1 has every club level on 0 points, so
         * {@link br.com.carvoeiromanager.competition.StandingsCalculator}'s final tie-break
         * (club name) alone decides "1st" vs "last" — and {@code AttendanceCalculator} prices that
         * arbitrary alphabetical order as if it were a real league position (rank 1 → x1.10, rank
         * 20 → x0.90), so a club named "AAA" would draw a materially bigger round-1 crowd than one
         * named "ZZZ" for no footballing reason at all.
         */
        public int leaguePosition(UUID clubId) {
            if (noFixturePlayedYet()) {
                return neutralPosition();
            }
            for (int i = 0; i < standingsAsOf.size(); i++) {
                if (standingsAsOf.get(i).clubId().equals(clubId)) {
                    return i + 1;
                }
            }
            // Defensive: club not found in the table. Fall back to a NEUTRAL mid-table position
            // rather than 1st — 1 is AttendanceCalculator's maximally favourable position factor,
            // and a defensive fallback should never be the best case.
            return neutralPosition();
        }

        /** True once every row in the as-of-matchDate table is still on zero played matches. */
        private boolean noFixturePlayedYet() {
            return standingsAsOf.stream().allMatch(row -> row.played() == 0);
        }

        /** The mid-table position: same value for every club, so no club is favoured over another. */
        private int neutralPosition() {
            return standingsAsOf.isEmpty() ? 1 : (standingsAsOf.size() + 1) / 2;
        }

        /** The number of clubs competing in this competition. */
        public int clubCount() {
            return standingsAsOf.size();
        }
    }
}
