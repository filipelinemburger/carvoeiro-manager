package br.com.carvoeiromanager.match;

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
 * needs — a club's recent form and its league position/club count — shared by the two places a
 * fixture gets finalised: {@link FixtureSimulator} (AI-simulated rounds) and
 * {@link LiveMatchService} (the managed club's live match). Extracted so both call sites compute
 * attendance identically rather than maintaining two copies of this lookup logic.
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
     * {@code clubId}'s points (3 win / 1 draw / 0 loss) from its last {@value #FORM_LOOKBACK}
     * PLAYED fixtures in {@code competitionId} strictly before {@code matchDate}, capped at
     * {@value #FORM_MAX_POINTS}. The strict "before" bound excludes the fixture currently being
     * resolved (and any other same-round fixture sharing its date) even though it may already be
     * marked played by the time this runs. Fewer than 5 played fixtures simply uses what exists
     * (early season).
     */
    public int recentFormPoints(UUID saveGameId, UUID competitionId, UUID clubId, LocalDate matchDate) {
        List<Fixture> recent = fixtureRepository.findBySaveGameIdAndCompetitionId(saveGameId, competitionId).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> f.getHomeClubId().equals(clubId) || f.getAwayClubId().equals(clubId))
                .filter(f -> f.getMatchDate() != null && matchDate != null && f.getMatchDate().isBefore(matchDate))
                .sorted(Comparator.comparing(Fixture::getMatchDate).thenComparing(Fixture::getRound).reversed())
                .limit(FORM_LOOKBACK)
                .toList();

        int points = recent.stream().mapToInt(f -> pointsFor(f, clubId)).sum();
        return Math.min(FORM_MAX_POINTS, points);
    }

    private static int pointsFor(Fixture fixture, UUID clubId) {
        boolean isHome = fixture.getHomeClubId().equals(clubId);
        int clubGoals = isHome ? fixture.getHomeGoals() : fixture.getAwayGoals();
        int opponentGoals = isHome ? fixture.getAwayGoals() : fixture.getHomeGoals();
        if (clubGoals > opponentGoals) return 3;
        if (clubGoals == opponentGoals) return 1;
        return 0;
    }

    /** {@code clubId}'s 1-based position in {@code competitionId}'s current standings. */
    public int leaguePosition(UUID saveGameId, UUID competitionId, UUID clubId) {
        List<StandingRow> table = standingsService.computeStandings(saveGameId, competitionId);
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).clubId().equals(clubId)) {
                return i + 1;
            }
        }
        return 1;   // club not found in the table (defensive; keeps AttendanceCalculator's position factor sane)
    }

    /** The number of clubs competing in {@code competitionId}. */
    public int clubCount(UUID saveGameId, UUID competitionId) {
        return standingsService.computeStandings(saveGameId, competitionId).size();
    }
}
