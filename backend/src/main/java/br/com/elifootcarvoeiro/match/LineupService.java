package br.com.elifootcarvoeiro.match;

import br.com.elifootcarvoeiro.career.SaveGameService;
import br.com.elifootcarvoeiro.club.Club;
import br.com.elifootcarvoeiro.club.ClubRepository;
import br.com.elifootcarvoeiro.contract.ContractValuation;
import br.com.elifootcarvoeiro.common.ApiException;
import br.com.elifootcarvoeiro.player.Marking;
import br.com.elifootcarvoeiro.player.Mentality;
import br.com.elifootcarvoeiro.player.Player;
import br.com.elifootcarvoeiro.player.PlayerRepository;
import br.com.elifootcarvoeiro.player.PlayerRole;
import br.com.elifootcarvoeiro.player.Position;
import br.com.elifootcarvoeiro.player.Pressing;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LineupService {

    private final SaveGameService saveGameService;
    private final PlayerRepository playerRepository;
    private final ClubRepository clubRepository;
    private final LineupRepository lineupRepository;
    private final LineupPlayerRepository lineupPlayerRepository;
    private final ContractValuation contractValuation;

    public LineupService(SaveGameService saveGameService,
                         PlayerRepository playerRepository,
                         ClubRepository clubRepository,
                         LineupRepository lineupRepository,
                         LineupPlayerRepository lineupPlayerRepository,
                         ContractValuation contractValuation) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.contractValuation = contractValuation;
    }

    /**
     * Returns all players belonging to the given club, mapped to PlayerResponse including role and state.
     * Enforces that the save game is owned by the user and the club belongs to that save game.
     */
    public SquadResponse getSquad(UUID saveGameId, UUID clubId, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);
        Map<UUID, PlayerRole> roleByPlayer = currentRoles(saveGameId, clubId);
        List<PlayerResponse> responses = playerRepository.findByClubId(clubId).stream()
                .map(p -> toPlayerResponse(p, roleByPlayer.get(p.getId())))
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
     * - No suspended player may be selected.
     * - Each role must be valid for the player's position.
     *
     * On conflict (lineup already exists): updates the formation and replaces all lineup_players
     * (delete-all + insert) so there are never leftover rows.
     */
    @Transactional
    public LineupResponse setLineup(UUID saveGameId, UUID clubId, SetLineupRequest req, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);

        List<LineupStarter> starters = req.starters();
        List<UUID> startingIds = starters == null ? List.of()
                : starters.stream().map(LineupStarter::playerId).toList();

        validatePlayerCount(startingIds);

        List<Player> clubPlayers = playerRepository.findByClubId(clubId);
        validatePlayersOwnedByClub(startingIds, clubPlayers);
        validateGkCount(startingIds, clubPlayers);
        validateNoIneligible(startingIds, clubPlayers);
        Map<UUID, PlayerRole> resolvedRoles = validateAndParseRoles(starters, clubPlayers);

        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId).orElse(null);

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
        for (LineupStarter starter : starters) {
            lineupPlayerRepository.save(
                    LineupPlayer.create(lineupId, starter.playerId(), true, resolvedRoles.get(starter.playerId())));
        }

        return new LineupResponse(lineupId, clubId, req.formation(), List.copyOf(starters));
    }

    /**
     * Returns the saved lineup (formation + starters with their roles) for the given
     * (saveGameId, clubId), or {@link Optional#empty()} if the club has never saved one.
     * Used by the frontend to rehydrate the Escalação screen on revisit.
     */
    public Optional<LineupResponse> getLineup(UUID saveGameId, UUID clubId, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> {
                    List<LineupStarter> starters = lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                            .filter(LineupPlayer::isStarting)
                            .map(lp -> new LineupStarter(lp.getPlayerId(), lp.getRole().name()))
                            .toList();
                    return new LineupResponse(lineup.getId(), clubId, lineup.getFormation(), starters);
                });
    }

    /**
     * Returns the persisted starting XI for the given (saveGameId, clubId) if one exists,
     * otherwise falls back to bestXi(clubId).
     */
    public List<Player> startingXi(UUID saveGameId, UUID clubId) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> {
                    List<UUID> playerIds = lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                            .filter(LineupPlayer::isStarting)
                            .map(LineupPlayer::getPlayerId)
                            .toList();
                    List<Player> eligibleXi = playerIds.stream()
                            .map(playerId -> playerRepository.findById(playerId)
                                    .orElseThrow(() -> new ApiException(
                                            "PLAYER_NOT_FOUND",
                                            "Player " + playerId + " not found",
                                            HttpStatus.NOT_FOUND)))
                            .filter(Player::isEligible)
                            .collect(java.util.stream.Collectors.toList());
                    // If suspensions/injuries left the saved XI short, fall back to bestXi so the
                    // engine always receives a full 11 (TeamStrengthCalculator divides by xi.size()).
                    return eligibleXi.size() == 11 ? eligibleXi : bestXi(clubId);
                })
                .orElseGet(() -> bestXi(clubId));
    }

    /**
     * Picks the best XI for a club: the top GK by overall + the top 10 outfield players by overall.
     * Eligible players (not suspended, not injured) are preferred. If there are fewer than 11
     * eligible players, ineligible players are used as fallback (ordered by overall) to ensure
     * the match engine always has a full XI. A missing GK is similarly filled from ineligible GKs.
     */
    public List<Player> bestXi(UUID clubId) {
        List<Player> allPlayers = playerRepository.findByClubId(clubId);
        List<Player> eligible = allPlayers.stream().filter(Player::isEligible).toList();

        // Try to find an eligible GK; fall back to any GK if none are eligible.
        Player bestGk = eligible.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(Comparator.comparingInt(Player::getOverall))
                .orElseGet(() -> allPlayers.stream()
                        .filter(p -> p.getPosition() == Position.GK)
                        .max(Comparator.comparingInt(Player::getOverall))
                        .orElseThrow(() -> new ApiException(
                                "NO_GK_FOUND",
                                "Club " + clubId + " has no goalkeeper at all",
                                HttpStatus.UNPROCESSABLE_CONTENT)));

        // Fill 10 outfield slots: prefer eligible, then fill from ineligible if needed.
        List<Player> eligibleOutfield = eligible.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(bestGk.getId()))
                .sorted(Comparator.comparingInt(Player::getOverall).reversed())
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));

        List<Player> topOutfield;
        if (eligibleOutfield.size() >= 10) {
            topOutfield = eligibleOutfield.subList(0, 10);
        } else {
            // Not enough eligible outfield players — fill with ineligible ones (graceful fallback).
            java.util.Set<UUID> usedIds = new java.util.HashSet<>();
            eligibleOutfield.forEach(p -> usedIds.add(p.getId()));
            usedIds.add(bestGk.getId());
            List<Player> ineligibleOutfield = allPlayers.stream()
                    .filter(p -> !usedIds.contains(p.getId()) && p.getPosition() != Position.GK)
                    .sorted(Comparator.comparingInt(Player::getOverall).reversed())
                    .toList();
            int needed = 10 - eligibleOutfield.size();
            List<Player> combined = new java.util.ArrayList<>(eligibleOutfield);
            combined.addAll(ineligibleOutfield.subList(0, Math.min(needed, ineligibleOutfield.size())));
            topOutfield = combined;
        }

        return new java.util.ArrayList<>(
                java.util.stream.Stream.concat(java.util.stream.Stream.of(bestGk), topOutfield.stream())
                        .toList()
        );
    }

    // --- Private validation helpers ---

    /**
     * Enforces save-game ownership and that the club belongs to the save game.
     * Throws {@code SAVE_GAME_NOT_FOUND} (404) when the save game is missing/foreign, and
     * {@code CLUB_NOT_FOUND} (404) when the club does not belong to the save game.
     */
    private void requireOwnedClub(UUID saveGameId, UUID clubId, UUID userId) {
        saveGameService.requireOwned(saveGameId, userId);
        Club club = clubRepository.findById(clubId)
                .orElseThrow(() -> clubNotFound());
        if (!club.getSaveGameId().equals(saveGameId)) {
            throw clubNotFound();
        }
    }

    private ApiException clubNotFound() {
        return new ApiException("CLUB_NOT_FOUND", "Club not found", HttpStatus.NOT_FOUND);
    }

    private void validatePlayerCount(List<UUID> startingIds) {
        if (startingIds == null || startingIds.size() != 11) {
            throw new ApiException(
                    "INVALID_LINEUP",
                    "A lineup must have exactly 11 starting players, but got " +
                            (startingIds == null ? 0 : startingIds.size()),
                    HttpStatus.UNPROCESSABLE_CONTENT);
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
                    HttpStatus.UNPROCESSABLE_CONTENT);
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
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private void validateNoIneligible(List<UUID> startingIds, List<Player> clubPlayers) {
        Set<UUID> startingSet = Set.copyOf(startingIds);
        List<UUID> suspended = clubPlayers.stream()
                .filter(p -> startingSet.contains(p.getId()))
                .filter(p -> p.getSuspensionRemaining() > 0)
                .map(Player::getId)
                .toList();
        if (!suspended.isEmpty()) {
            throw new ApiException("SUSPENDED_PLAYER",
                    "These players are suspended and cannot be selected: " + suspended,
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
        List<UUID> injured = clubPlayers.stream()
                .filter(p -> startingSet.contains(p.getId()))
                .filter(p -> p.getInjuryMatches() > 0)
                .map(Player::getId)
                .toList();
        if (!injured.isEmpty()) {
            throw new ApiException("INJURED_PLAYER",
                    "These players are injured and cannot be selected: " + injured,
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Map<UUID, PlayerRole> validateAndParseRoles(List<LineupStarter> starters, List<Player> clubPlayers) {
        Map<UUID, Player> byId = clubPlayers.stream().collect(Collectors.toMap(Player::getId, p -> p));
        Map<UUID, PlayerRole> resolved = new HashMap<>();
        for (LineupStarter starter : starters) {
            PlayerRole role;
            try {
                role = PlayerRole.valueOf(starter.role());
            } catch (IllegalArgumentException ex) {
                throw new ApiException("INVALID_ROLE",
                        "Unknown role: " + starter.role(), HttpStatus.UNPROCESSABLE_CONTENT);
            }
            // player is guaranteed non-null in practice: validatePlayersOwnedByClub
            // runs before this and rejects any starter not in clubPlayers. The guard
            // is defensive only.
            Player player = byId.get(starter.playerId());
            if (player != null && !role.isValidFor(player.getPosition())) {
                throw new ApiException("INVALID_ROLE",
                        "Role " + role + " is not valid for position " + player.getPosition(),
                        HttpStatus.UNPROCESSABLE_CONTENT);
            }
            resolved.put(starter.playerId(), role);
        }
        return resolved;
    }

    private Map<UUID, PlayerRole> currentRoles(UUID saveGameId, UUID clubId) {
        Map<UUID, PlayerRole> map = new HashMap<>();
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId).ifPresent(lineup ->
                lineupPlayerRepository.findByLineupId(lineup.getId()).forEach(lp ->
                        map.put(lp.getPlayerId(), lp.getRole())));
        return map;
    }

    private PlayerResponse toPlayerResponse(Player p, PlayerRole role) {
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
                p.getStamina(),
                p.getForm(),
                p.getYellowCards(),
                p.getSuspensionRemaining(),
                p.getInjuryMatches(),
                p.isEligible(),
                role != null ? role.name() : null,
                p.getContractYears(),
                contractValuation.wageDemand(p.getOverall(), p.getAge()));
    }

    @Transactional
    public TacticsResponse setTactics(UUID saveGameId, UUID clubId, SetTacticsRequest req, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);

        Mentality mentality = parseMentality(req.mentality());
        Pressing pressing = parsePressing(req.pressing());
        Marking marking = parseMarking(req.marking());

        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .orElseThrow(() -> new ApiException("LINEUP_REQUIRED",
                        "Set a lineup before configuring tactics", HttpStatus.UNPROCESSABLE_CONTENT));

        Set<UUID> xiIds = lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                .filter(LineupPlayer::isStarting)
                .map(LineupPlayer::getPlayerId)
                .collect(Collectors.toSet());
        requireTakerInXi(req.penaltyTakerId(), xiIds);
        requireTakerInXi(req.freekickTakerId(), xiIds);
        requireTakerInXi(req.cornerTakerId(), xiIds);

        lineup.setMentality(mentality);
        lineup.setPressing(pressing);
        lineup.setMarking(marking);
        lineup.setPenaltyTakerId(req.penaltyTakerId());
        lineup.setFreekickTakerId(req.freekickTakerId());
        lineup.setCornerTakerId(req.cornerTakerId());
        lineupRepository.save(lineup);

        return toTacticsResponse(lineup);
    }

    public TacticsResponse getTactics(UUID saveGameId, UUID clubId, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(this::toTacticsResponse)
                .orElseGet(() -> new TacticsResponse(
                        Mentality.BALANCED.name(), Pressing.MEDIUM.name(), Marking.ZONAL.name(),
                        null, null, null));
    }

    private TacticsResponse toTacticsResponse(Lineup lineup) {
        return new TacticsResponse(
                lineup.getMentality().name(), lineup.getPressing().name(), lineup.getMarking().name(),
                lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId());
    }

    private void requireTakerInXi(UUID takerId, Set<UUID> xiIds) {
        if (takerId != null && !xiIds.contains(takerId)) {
            throw new ApiException("INVALID_TACTICS",
                    "Set-piece taker " + takerId + " is not in the starting XI",
                    HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Mentality parseMentality(String v) {
        try { return Mentality.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_TACTICS", "Unknown mentality: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Pressing parsePressing(String v) {
        try { return Pressing.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_TACTICS", "Unknown pressing: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }

    private Marking parseMarking(String v) {
        try { return Marking.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException("INVALID_TACTICS", "Unknown marking: " + v, HttpStatus.UNPROCESSABLE_CONTENT);
        }
    }
}
