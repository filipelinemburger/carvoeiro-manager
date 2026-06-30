package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.club.Club;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure (non-bean) calculator that builds a sorted league table from a list of clubs
 * and a list of already-played fixtures.
 *
 * Tiebreaker order: points desc → goal difference desc → goals for desc → club name asc.
 */
public class StandingsCalculator {

    public List<StandingRow> compute(List<Club> clubs, List<Fixture> playedFixtures) {
        Map<UUID, MutableRow> rows = new HashMap<>();

        for (Club club : clubs) {
            rows.put(club.getId(), new MutableRow(club.getId(), club.getName()));
        }

        for (Fixture fixture : playedFixtures) {
            if (!fixture.isPlayed() || fixture.getHomeGoals() == null || fixture.getAwayGoals() == null) {
                continue;
            }

            MutableRow home = rows.get(fixture.getHomeClubId());
            MutableRow away = rows.get(fixture.getAwayClubId());

            if (home == null || away == null) {
                continue;
            }

            final int homeGoals = fixture.getHomeGoals();
            final int awayGoals = fixture.getAwayGoals();

            home.played++;
            away.played++;
            home.goalsFor += homeGoals;
            home.goalsAgainst += awayGoals;
            away.goalsFor += awayGoals;
            away.goalsAgainst += homeGoals;

            if (homeGoals > awayGoals) {
                home.won++;
                away.lost++;
            } else if (homeGoals < awayGoals) {
                away.won++;
                home.lost++;
            } else {
                home.drawn++;
                away.drawn++;
            }
        }

        Comparator<StandingRow> order = Comparator
                .<StandingRow>comparingInt(StandingRow::points).reversed()
                .thenComparing(Comparator.<StandingRow>comparingInt(StandingRow::goalDifference).reversed())
                .thenComparing(Comparator.<StandingRow>comparingInt(StandingRow::goalsFor).reversed())
                .thenComparing(StandingRow::clubName);

        return rows.values().stream()
                .map(MutableRow::toStandingRow)
                .sorted(order)
                .toList();
    }

    private static final class MutableRow {
        final UUID clubId;
        final String clubName;
        int played;
        int won;
        int drawn;
        int lost;
        int goalsFor;
        int goalsAgainst;

        MutableRow(UUID clubId, String clubName) {
            this.clubId = clubId;
            this.clubName = clubName;
        }

        StandingRow toStandingRow() {
            final int points = won * 3 + drawn;
            final int goalDifference = goalsFor - goalsAgainst;
            return new StandingRow(clubId, clubName, played, won, drawn, lost,
                    goalsFor, goalsAgainst, goalDifference, points);
        }
    }
}
