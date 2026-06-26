package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.Position;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LineupService {

    private final PlayerRepository playerRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;

    public LineupService(PlayerRepository playerRepository,
                         LineupRepository lineupRepository,
                         LineupPlayerRepository lineupPlayerRepository) {
        this.playerRepository = playerRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
    }

    /**
     * Returns all players belonging to the given club, mapped to PlayerResponse.
     */
    public SquadResponse getSquad(UUID clubId) {
        List<PlayerResponse> responses = playerRepository.findByClubId(clubId).stream()
                .map(this::toPlayerResponse)
                .toList();
        return new SquadResponse(responses);
    }

    /**
     * Validates and upserts a lineup for the given (saveGameId, clubId).
     *
     * Validation rules:
     * - Exactly 11 starting players.
     * - Exactly 1 GK among them.
     * - Every player ID must belong to this club.
     *
     * On conflict (lineup already exists): updates the formation and replaces all lineup_players
     * (delete-all + insert) so there are never leftover rows.
     */
    @Transactional
    public LineupResponse setLineup(UUID saveGameId, UUID clubId, SetLineupRequest req) {
        List<UUID> startingIds = req.startingPlayerIds();

        validatePlayerCount(startingIds);

        List<Player> clubPlayers = playerRepository.findByClubId(clubId);
        validatePlayersOwnedByClub(startingIds, clubPlayers);
        validateGkCount(startingIds, clubPlayers);

        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .orElse(null);

        if (lineup == null) {
            lineup = Lineup.create(saveGameId, clubId, req.formation());
            lineupRepository.save(lineup);
        } else {
            lineup.setFormation(req.formation());
            lineupRepository.save(lineup);
            // Remove all previous lineup_players to avoid stale rows
            lineupPlayerRepository.deleteByLineupId(lineup.getId());
        }

        final UUID lineupId = lineup.getId();
        for (UUID playerId : startingIds) {
            lineupPlayerRepository.save(LineupPlayer.create(lineupId, playerId, true));
        }

        return new LineupResponse(lineupId, clubId, req.formation(), List.copyOf(startingIds));
    }

    /**
     * Picks the best XI for a club: the top GK by overall + the top 10 outfield players by overall.
     * Assumes the club has at least 1 GK and at least 10 outfield players (guaranteed by seed data).
     */
    public List<Player> bestXi(UUID clubId) {
        List<Player> allPlayers = playerRepository.findByClubId(clubId);

        Player bestGk = allPlayers.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(Comparator.comparingInt(Player::getOverall))
                .orElseThrow(() -> new ApiException(
                        "NO_GK_FOUND",
                        "Club " + clubId + " has no goalkeeper",
                        HttpStatus.UNPROCESSABLE_ENTITY));

        List<Player> topOutfield = allPlayers.stream()
                .filter(p -> p.getPosition() != Position.GK)
                .sorted(Comparator.comparingInt(Player::getOverall).reversed())
                .limit(10)
                .toList();

        return new java.util.ArrayList<>(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(bestGk), topOutfield.stream())
                        .toList()
        );
    }

    // --- Private validation helpers ---

    private void validatePlayerCount(List<UUID> startingIds) {
        if (startingIds == null || startingIds.size() != 11) {
            throw new ApiException(
                    "INVALID_LINEUP",
                    "A lineup must have exactly 11 starting players, but got " +
                            (startingIds == null ? 0 : startingIds.size()),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void validatePlayersOwnedByClub(List<UUID> startingIds, List<Player> clubPlayers) {
        Set<UUID> clubPlayerIds = clubPlayers.stream()
                .map(Player::getId)
                .collect(Collectors.toSet());

        List<UUID> foreignIds = startingIds.stream()
                .filter(id -> !clubPlayerIds.contains(id))
                .toList();

        if (!foreignIds.isEmpty()) {
            throw new ApiException(
                    "INVALID_LINEUP",
                    "The following player IDs do not belong to this club: " + foreignIds,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private void validateGkCount(List<UUID> startingIds, List<Player> clubPlayers) {
        Set<UUID> startingSet = Set.copyOf(startingIds);

        long gkCount = clubPlayers.stream()
                .filter(p -> startingSet.contains(p.getId()))
                .filter(p -> p.getPosition() == Position.GK)
                .count();

        if (gkCount != 1) {
            throw new ApiException(
                    "INVALID_LINEUP",
                    "A lineup must have exactly 1 goalkeeper, but found " + gkCount,
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private PlayerResponse toPlayerResponse(Player p) {
        return new PlayerResponse(
                p.getId(),
                p.getName(),
                p.getPosition().name(),
                p.getAge(),
                p.getOverall(),
                p.getPace(),
                p.getFinishing(),
                p.getPassing(),
                p.getTackling(),
                p.getGoalkeeping(),
                p.getStamina()
        );
    }
}
