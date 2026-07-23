package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.contract.ContractValuation;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.TrainingIntensity;
import br.com.carvoeiromanager.transfer.TransferValuation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
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
    private final TransferValuation transferValuation;
    private final FitnessCalculator fitnessCalculator;

    public LineupService(SaveGameService saveGameService,
                         PlayerRepository playerRepository,
                         ClubRepository clubRepository,
                         LineupRepository lineupRepository,
                         LineupPlayerRepository lineupPlayerRepository,
                         ContractValuation contractValuation,
                         TransferValuation transferValuation,
                         FitnessCalculator fitnessCalculator) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.contractValuation = contractValuation;
        this.transferValuation = transferValuation;
        this.fitnessCalculator = fitnessCalculator;
    }

    /**
     * Returns all players belonging to the given club, mapped to PlayerResponse including role and state.
     * Enforces that the save game is owned by the user and the club belongs to that save game.
     */
    public SquadResponse getSquad(UUID saveGameId, UUID clubId, UUID userId) {
        LocalDate onDate = saveGameService.requireOwned(saveGameId, userId).getCurrentDate();
        Club club = clubRepository.findById(clubId).orElseThrow(this::clubNotFound);
        if (!club.getSaveGameId().equals(saveGameId)) {
            throw clubNotFound();
        }
        Map<UUID, PlayerRole> roleByPlayer = currentRoles(saveGameId, clubId);
        List<PlayerResponse> responses = playerRepository.findByClubIdAndYouthProspectFalse(clubId).stream()
                .map(p -> toPlayerResponse(p, roleByPlayer.get(p.getId()), onDate))
                .toList();
        return new SquadResponse(responses, club.getPrimaryColor(), club.getSecondaryColor());
    }

    /**
     * Full player details for any player in the save game — a club's squad member (their
     * current tactical role, if any) or a free agent (role null, since free agents have no
     * club and therefore no lineup). Used by contexts that only have a bare player id (transfer
     * market, free agents) and need the full attribute board, not just the lean summary those
     * list endpoints return.
     */
    public PlayerResponse getPlayer(UUID saveGameId, UUID playerId, UUID userId) {
        LocalDate onDate = saveGameService.requireOwned(saveGameId, userId).getCurrentDate();
        Player player = playerRepository.findById(playerId)
                .filter(p -> p.getSaveGameId().equals(saveGameId))
                .orElseThrow(() -> new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player " + playerId + " not found"));
        PlayerRole role = player.getClubId() != null
                ? currentRoles(saveGameId, player.getClubId()).get(player.getId())
                : null;
        return toPlayerResponse(player, role, onDate);
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
        List<LineupStarter> bench = req.bench();
        List<UUID> startingIds = starters == null ? List.of()
                : starters.stream().map(LineupStarter::playerId).toList();
        List<UUID> benchIds = bench.stream().map(LineupStarter::playerId).toList();

        validatePlayerCount(startingIds);
        validateBench(startingIds, benchIds);

        List<Player> clubPlayers = playerRepository.findByClubIdAndYouthProspectFalse(clubId);
        validatePlayersOwnedByClub(startingIds, clubPlayers);
        validatePlayersOwnedByClub(benchIds, clubPlayers);
        validateGkCount(startingIds, clubPlayers);
        validateNoIneligible(startingIds, clubPlayers);
        validateNoIneligible(benchIds, clubPlayers);
        Map<UUID, PlayerRole> starterRoles = validateAndParseRoles(starters, clubPlayers);
        Map<UUID, PlayerRole> benchRoles = validateAndParseRoles(bench, clubPlayers);

        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId).orElse(null);

        if (lineup == null) {
            lineup = Lineup.create(saveGameId, clubId, req.formation());
            lineupRepository.save(lineup);
        } else {
            lineup.setFormation(req.formation());
            lineupRepository.save(lineup);
            lineupPlayerRepository.deleteByLineupId(lineup.getId());
        }

        final UUID lineupId = lineup.getId();
        // starters is guaranteed non-null and size == 11 here: validatePlayerCount threw earlier if not
        for (LineupStarter starter : starters) {
            lineupPlayerRepository.save(
                    LineupPlayer.create(lineupId, starter.playerId(), true, starterRoles.get(starter.playerId())));
        }
        for (LineupStarter sub : bench) {
            lineupPlayerRepository.save(
                    LineupPlayer.create(lineupId, sub.playerId(), false, benchRoles.get(sub.playerId())));
        }

        return new LineupResponse(lineupId, clubId, req.formation(), List.copyOf(starters), List.copyOf(bench));
    }

    private static final Set<Attribute> TRAINABLE_ATTRIBUTES = Set.of(
            Attribute.VELOCIDADE, Attribute.FINALIZACAO, Attribute.PASSE,
            Attribute.DESARME, Attribute.REFLEXOS, Attribute.FOLEGO,
            Attribute.CABECEIO, Attribute.CRUZAMENTO, Attribute.MARCACAO,
            Attribute.DRIBLE, Attribute.VISAO, Attribute.FORCA);

    /**
     * Bulk-sets each listed player's training focus + intensity for the club within a save game.
     * Every player must already belong to this club (as returned by
     * {@code findByClubIdAndYouthProspectFalse} — youth prospects are never trainable).
     */
    @Transactional
    public SquadResponse setTraining(UUID saveGameId, UUID clubId, SetTrainingRequest req, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);

        Map<UUID, Player> clubPlayers = playerRepository.findByClubIdAndYouthProspectFalse(clubId).stream()
                .collect(Collectors.toMap(Player::getId, p -> p));
        for (TrainingAssignment assignment : req.assignments()) {
            Player player = clubPlayers.get(assignment.playerId());
            if (player == null) {
                throw new ApiException(ErrorCode.PLAYER_NOT_FOUND,
                        "Player " + assignment.playerId() + " not found in this club");
            }
            Attribute focus = parseTrainingFocus(assignment.focus());
            TrainingIntensity intensity;
            try {
                intensity = TrainingIntensity.valueOf(assignment.intensity());
            } catch (IllegalArgumentException e) {
                throw new ApiException(ErrorCode.INVALID_TRAINING_FOCUS,
                        "Unknown training intensity: " + assignment.intensity());
            }
            player.setTrainingAssignment(focus, intensity);
        }
        playerRepository.saveAll(clubPlayers.values());

        return getSquad(saveGameId, clubId, userId);
    }

    private Attribute parseTrainingFocus(String raw) {
        if (raw == null) {
            return null;
        }
        Attribute attribute;
        try {
            attribute = Attribute.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TRAINING_FOCUS, "Unknown attribute: " + raw);
        }
        if (!TRAINABLE_ATTRIBUTES.contains(attribute)) {
            throw new ApiException(ErrorCode.INVALID_TRAINING_FOCUS, "Attribute is not trainable: " + raw);
        }
        return attribute;
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
                    List<LineupPlayer> rows = lineupPlayerRepository.findByLineupId(lineup.getId());
                    List<LineupStarter> starters = rows.stream()
                            .filter(LineupPlayer::isStarting)
                            .map(lp -> new LineupStarter(lp.getPlayerId(), lp.getRole().name()))
                            .toList();
                    List<LineupStarter> bench = rows.stream()
                            .filter(lp -> !lp.isStarting())
                            .map(lp -> new LineupStarter(lp.getPlayerId(), lp.getRole().name()))
                            .toList();
                    return new LineupResponse(lineup.getId(), clubId, lineup.getFormation(), starters, bench);
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
                                            ErrorCode.PLAYER_NOT_FOUND, "Player " + playerId + " not found")))
                            .filter(Player::isEligible)
                            .collect(java.util.stream.Collectors.toList());
                    // If suspensions/injuries left the saved XI short, fall back to bestXi so the
                    // engine always receives a full 11 (TeamStrengthCalculator divides by xi.size()).
                    return eligibleXi.size() == 11 ? eligibleXi : bestXi(clubId);
                })
                .orElseGet(() -> bestXi(clubId));
    }

    /**
     * Returns the players in the saved starting XI for (saveGameId, clubId) who are currently
     * ineligible (suspended or injured). Empty when there is no saved lineup or all starters are
     * eligible. Used by the advance preview to warn the manager before a match they would play
     * with an ineligible starter still selected.
     */
    public List<Player> savedIneligibleStarters(UUID saveGameId, UUID clubId) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                        .filter(LineupPlayer::isStarting)
                        .map(lp -> playerRepository.findById(lp.getPlayerId()).orElse(null))
                        .filter(p -> p != null && !p.isEligible())
                        .toList())
                .orElseGet(List::of);
    }

    /** The saved bench (starting=false) for a club, resolved to Players; empty when none saved. */
    public List<Player> benchPlayers(UUID saveGameId, UUID clubId) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                        .filter(lp -> !lp.isStarting())
                        .map(lp -> playerRepository.findById(lp.getPlayerId()).orElse(null))
                        .filter(p -> p != null && p.isEligible())
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Returns saved starters whose effective fitness at {@code onDate} is below {@code threshold}.
     * Non-blocking soft warning — callers must NOT use this to gate any advance.
     * Returns an empty list when no lineup has been saved.
     */
    public List<AdvancePreviewLowFitness> lowFitnessStarters(UUID saveGameId, UUID clubId,
                                                              LocalDate onDate, int threshold) {
        return lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .map(lineup -> lineupPlayerRepository.findByLineupId(lineup.getId()).stream()
                        .filter(LineupPlayer::isStarting)
                        .map(lp -> playerRepository.findById(lp.getPlayerId()).orElse(null))
                        .filter(p -> p != null)
                        .map(p -> new AdvancePreviewLowFitness(
                                p.getId().toString(),
                                p.getName(),
                                fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge())))
                        .filter(row -> row.fitness() < threshold)
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Picks the best XI for a club using raw overall for ranking (no fitness adjustment).
     * Delegates to {@link #bestXi(UUID, LocalDate)} with a null date.
     */
    public List<Player> bestXi(UUID clubId) {
        return bestXi(clubId, null);
    }

    /**
     * Picks the best XI for a club, ranking candidates by a fitness-adjusted rating when
     * {@code onDate} is supplied, or by raw overall when {@code onDate} is null.
     * <p>
     * Eligible players (not suspended, not injured) are preferred. If there are fewer than 11
     * eligible players, ineligible players are used as fallback (ordered by rating) to ensure
     * the match engine always has a full XI. A missing GK is similarly filled from ineligible GKs.
     */
    public List<Player> bestXi(UUID clubId, LocalDate onDate) {
        List<Player> allPlayers = playerRepository.findByClubIdAndYouthProspectFalse(clubId);
        List<Player> eligible = allPlayers.stream().filter(Player::isEligible).toList();

        // Ascending comparator: .max(byRating) yields the highest-rated player,
        // and .sorted(byRating.reversed()) yields best-first (top 10 outfield).
        // Secondary tie-break on id (a deterministic total order) so that rating ties near
        // the XI/bench cutoff resolve identically across repeated calls regardless of the
        // DB row order (findByClubIdAndYouthProspectFalse has no ORDER BY) — required because
        // this method is now called twice for the same fixture (parallel pre-simulation at
        // kickoff, then attribution at finish) and both calls must select the identical XI.
        Comparator<Player> byRating =
                Comparator.comparingDouble((Player p) -> rating(p, onDate))
                        .thenComparing(Player::getId);

        // Try to find an eligible GK; fall back to any GK if none are eligible.
        Player bestGk = eligible.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(byRating)
                .orElseGet(() -> allPlayers.stream()
                        .filter(p -> p.getPosition() == Position.GK)
                        .max(byRating)
                        .orElseThrow(() -> new ApiException(
                                ErrorCode.NO_GK_FOUND, "Club " + clubId + " has no goalkeeper at all")));

        // Fill 10 outfield slots: prefer eligible, then fill from ineligible if needed.
        List<Player> eligibleOutfield = eligible.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(bestGk.getId()))
                .sorted(byRating.reversed())
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
                    .sorted(byRating.reversed())
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

    private static final int BENCH_SIZE = 7;

    /**
     * Best available bench for a club without a saved lineup (AI clubs): the best remaining
     * eligible GK first (injury cover for the keeper), then the best other eligible non-youth
     * players by overall, up to {@value #BENCH_SIZE} total. Players already in the XI are
     * excluded. Unlike {@link #bestXi}, there is no ineligible fallback — a short (even empty)
     * bench is fine.
     */
    public List<Player> bestBench(UUID clubId, List<Player> xi) {
        Set<UUID> xiIds = xi.stream().map(Player::getId).collect(Collectors.toSet());
        List<Player> candidates = playerRepository.findByClubIdAndYouthProspectFalse(clubId).stream()
                .filter(Player::isEligible)
                .filter(p -> !xiIds.contains(p.getId()))
                .toList();

        // Same deterministic id tie-break as bestXi's byRating comparator (see there for why).
        Comparator<Player> byOverall = Comparator.comparingInt(Player::getOverall).thenComparing(Player::getId);

        List<Player> bench = new ArrayList<>();
        candidates.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(byOverall)
                .ifPresent(bench::add);
        candidates.stream()
                .filter(p -> bench.stream().noneMatch(b -> b.getId().equals(p.getId())))
                .sorted(byOverall.reversed())
                .limit(BENCH_SIZE - bench.size())
                .forEach(bench::add);
        return bench;
    }

    /** Fitness-adjusted rating used to rank candidates; raw overall when no date is supplied. */
    private double rating(Player p, LocalDate onDate) {
        if (onDate == null) {
            return p.getOverall();
        }
        int fit = fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge());
        return p.getOverall() * fitnessCalculator.fitnessMult(fit);
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
        return new ApiException(ErrorCode.CLUB_NOT_FOUND, "Club not found");
    }

    private void validatePlayerCount(List<UUID> startingIds) {
        if (startingIds == null || startingIds.size() != 11) {
            throw new ApiException(
                    ErrorCode.INVALID_LINEUP,
                    "A lineup must have exactly 11 starting players, but got " +
                            (startingIds == null ? 0 : startingIds.size()));
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
                    ErrorCode.INVALID_LINEUP,
                    "The following player IDs do not belong to this club: " + foreignIds);
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
                    ErrorCode.INVALID_LINEUP,
                    "A lineup must have exactly 1 goalkeeper, but found " + gkCount);
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
            throw new ApiException(ErrorCode.SUSPENDED_PLAYER,
                    "These players are suspended and cannot be selected: " + suspended);
        }
        List<UUID> injured = clubPlayers.stream()
                .filter(p -> startingSet.contains(p.getId()))
                .filter(p -> p.getInjuryMatches() > 0)
                .map(Player::getId)
                .toList();
        if (!injured.isEmpty()) {
            throw new ApiException(ErrorCode.INJURED_PLAYER,
                    "These players are injured and cannot be selected: " + injured);
        }
    }

    private void validateBench(List<UUID> startingIds, List<UUID> benchIds) {
        if (benchIds.size() > 9) {
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "The bench may hold at most 9 players, but got " + benchIds.size());
        }
        if (Set.copyOf(benchIds).size() != benchIds.size()) {
            throw new ApiException(ErrorCode.INVALID_LINEUP, "The bench contains duplicate players");
        }
        Set<UUID> starting = Set.copyOf(startingIds);
        List<UUID> overlap = benchIds.stream().filter(starting::contains).toList();
        if (!overlap.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "These players are both starting and on the bench: " + overlap);
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
                throw new ApiException(ErrorCode.INVALID_ROLE, "Unknown role: " + starter.role());
            }
            // player is guaranteed non-null in practice: validatePlayersOwnedByClub
            // runs before this and rejects any starter not in clubPlayers. The guard
            // is defensive only.
            Player player = byId.get(starter.playerId());
            if (player != null && !role.isValidFor(player.getPosition())) {
                throw new ApiException(ErrorCode.INVALID_ROLE,
                        "Role " + role + " is not valid for position " + player.getPosition());
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

    private PlayerResponse toPlayerResponse(Player p, PlayerRole role, LocalDate onDate) {
        int fitness = fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge());
        List<AttributeView> attributes = java.util.Arrays.stream(Attribute.values())
                .map(a -> new AttributeView(a.name(), a.label(), a.group().name(), p.attribute(a)))
                .toList();
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
                p.getMorale(),
                p.getYellowCards(),
                p.getSuspensionRemaining(),
                p.getInjuryMatches(),
                p.isEligible(),
                role != null ? role.name() : null,
                p.getContractYears(),
                transferValuation.marketValue(p.getOverall(), p.getAge()),
                contractValuation.wageDemand(p.getOverall(), p.getAge()),
                fitness,
                p.getInjurySeverity().name(),
                attributes,
                p.isTransferListed(),
                p.getListedReason() != null ? p.getListedReason().name() : null,
                p.getTrainingFocus() != null ? p.getTrainingFocus().name() : null,
                p.getTrainingIntensity().name());
    }

    @Transactional
    public TacticsResponse setTactics(UUID saveGameId, UUID clubId, SetTacticsRequest req, UUID userId) {
        requireOwnedClub(saveGameId, clubId, userId);

        Mentality mentality = parseMentality(req.mentality());
        Pressing pressing = parsePressing(req.pressing());
        Marking marking = parseMarking(req.marking());

        Lineup lineup = lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId)
                .orElseThrow(() -> new ApiException(ErrorCode.LINEUP_REQUIRED,
                        "Set a lineup before configuring tactics"));

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
        // gameStyle is optional: only overwrite the persisted style when the client actually sends one.
        // A null/blank value (pre-GameStyle-UI clients) must NOT wipe a previously chosen style.
        if (req.gameStyle() != null && !req.gameStyle().isBlank()) {
            lineup.setGameStyle(parseGameStyle(req.gameStyle()));
        }
        // attackZone is optional in the same way: a null/blank value (pre-AttackZone-UI clients) leaves
        // the persisted zone unchanged rather than resetting it to MISTO.
        if (req.attackZone() != null && !req.attackZone().isBlank()) {
            lineup.setAttackZone(parseAttackZone(req.attackZone()));
        }
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
                        Mentality.BALANCED.name(), GameStyle.EQUILIBRADO.name(), AttackZone.MISTO.name(),
                        Pressing.MEDIUM.name(), Marking.ZONAL.name(),
                        null, null, null));
    }

    private TacticsResponse toTacticsResponse(Lineup lineup) {
        return new TacticsResponse(
                lineup.getMentality().name(), lineup.getGameStyle().name(), lineup.getAttackZone().name(),
                lineup.getPressing().name(), lineup.getMarking().name(),
                lineup.getPenaltyTakerId(), lineup.getFreekickTakerId(), lineup.getCornerTakerId());
    }

    private void requireTakerInXi(UUID takerId, Set<UUID> xiIds) {
        if (takerId != null && !xiIds.contains(takerId)) {
            throw new ApiException(ErrorCode.INVALID_TACTICS,
                    "Set-piece taker " + takerId + " is not in the starting XI");
        }
    }

    private Mentality parseMentality(String v) {
        try { return Mentality.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown mentality: " + v);
        }
    }

    private GameStyle parseGameStyle(String v) {
        try { return GameStyle.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Estilo de jogo inválido: " + v);
        }
    }

    private AttackZone parseAttackZone(String v) {
        try { return AttackZone.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Zona de ataque inválida: " + v);
        }
    }

    private Pressing parsePressing(String v) {
        try { return Pressing.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown pressing: " + v);
        }
    }

    private Marking parseMarking(String v) {
        try { return Marking.valueOf(v); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown marking: " + v);
        }
    }
}
