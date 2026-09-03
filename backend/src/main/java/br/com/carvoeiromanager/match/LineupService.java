package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.contract.ContractValuation;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.loan.LoanEligibility;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.DetailedPosition;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerInstruction;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.player.RoleArchetype;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.player.RoleFocus;
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
    private final LoanEligibility loanEligibility;

    public LineupService(SaveGameService saveGameService,
                         PlayerRepository playerRepository,
                         ClubRepository clubRepository,
                         LineupRepository lineupRepository,
                         LineupPlayerRepository lineupPlayerRepository,
                         ContractValuation contractValuation,
                         TransferValuation transferValuation,
                         FitnessCalculator fitnessCalculator,
                         LoanEligibility loanEligibility) {
        this.saveGameService = saveGameService;
        this.playerRepository = playerRepository;
        this.clubRepository = clubRepository;
        this.lineupRepository = lineupRepository;
        this.lineupPlayerRepository = lineupPlayerRepository;
        this.contractValuation = contractValuation;
        this.transferValuation = transferValuation;
        this.fitnessCalculator = fitnessCalculator;
        this.loanEligibility = loanEligibility;
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
        Map<UUID, RoleAssignment> roleByPlayer = currentRoles(saveGameId, clubId);
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
        RoleAssignment role = player.getClubId() != null
                ? currentRoles(saveGameId, player.getClubId()).get(player.getId())
                : null;
        return toPlayerResponse(player, role, onDate);
    }

    /**
     * Validates and upserts a lineup for the given (saveGameId, clubId).
     *
     * Validation rules:
     * - Exactly 11 starting players.
     * - At most 1 GK among them (spec §5.1: an outfielder may be improvised in goal).
     * - Every player ID must belong to this club.
     * - No suspended player may be selected.
     * - Each archetype+focus must be valid for the slot (or, without slots, for the player).
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
        // Slot structure first: role validity is measured against the pinned slot, so a malformed
        // slotIndex must be reported as such rather than surfacing as a confusing role error.
        validateSlotIndexes(starters, bench);
        Map<UUID, RoleAssignment> starterRoles =
                validateAndParseAssignments(starters, clubPlayers, req.formation());
        // Bench entries never carry a slot (validateSlotIndexes just proved it), so they always
        // validate against the player's own positions.
        Map<UUID, RoleAssignment> benchRoles = validateAndParseAssignments(bench, clubPlayers, req.formation());
        // Parse + validate personal instructions BEFORE any delete/save, mirroring the roles above,
        // so a bad instruction (unknown value, or one on a GK) rejects the whole request without
        // partially mutating the persisted lineup.
        Map<UUID, PlayerInstruction> starterInstructions = validateAndParseInstructions(starters, clubPlayers);
        Map<UUID, PlayerInstruction> benchInstructions = validateAndParseInstructions(bench, clubPlayers);

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
            RoleAssignment assignment = starterRoles.get(starter.playerId());
            LineupPlayer lp = LineupPlayer.create(lineupId, starter.playerId(), true,
                    assignment.archetype(), assignment.focus());
            lp.setInstruction(starterInstructions.get(starter.playerId()));
            lp.setSlotIndex(starter.slotIndex());
            lineupPlayerRepository.save(lp);
        }
        for (LineupStarter sub : bench) {
            RoleAssignment assignment = benchRoles.get(sub.playerId());
            LineupPlayer lp = LineupPlayer.create(lineupId, sub.playerId(), false,
                    assignment.archetype(), assignment.focus());
            lp.setInstruction(benchInstructions.get(sub.playerId()));
            lineupPlayerRepository.save(lp);
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
                            .map(this::toLineupStarter)
                            .toList();
                    List<LineupStarter> bench = rows.stream()
                            .filter(lp -> !lp.isStarting())
                            .map(this::toLineupStarter)
                            .toList();
                    return new LineupResponse(lineup.getId(), clubId, lineup.getFormation(), starters, bench);
                });
    }

    /**
     * Returns the persisted starting XI for the given (saveGameId, clubId) if one exists,
     * otherwise falls back to bestXi(clubId). Delegates to the opponent-aware overload with a
     * null opponent, which imposes no loan restriction.
     */
    public List<Player> startingXi(UUID saveGameId, UUID clubId) {
        return startingXi(saveGameId, clubId, null);
    }

    /**
     * Same as {@link #startingXi(UUID, UUID)}, but also excludes a saved starter who is on loan
     * and barred from facing {@code opponentClubId} (see {@link LoanEligibility}) — such a player
     * behaves exactly like a suspended one, falling through to {@link #bestXi} when he leaves the
     * saved XI short.
     */
    public List<Player> startingXi(UUID saveGameId, UUID clubId, UUID opponentClubId) {
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
                            .filter(p -> loanEligibility.mayFace(p, opponentClubId))
                            .collect(java.util.stream.Collectors.toList());
                    // If suspensions/injuries/loan restrictions left the saved XI short, fall back to
                    // bestXi so the engine always receives a full 11 (TeamStrengthCalculator divides
                    // by xi.size()).
                    return eligibleXi.size() == 11 ? eligibleXi : bestXi(clubId, null, opponentClubId);
                })
                .orElseGet(() -> bestXi(clubId, null, opponentClubId));
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
     * the match engine always has a full XI. A missing GK is similarly filled from ineligible GKs,
     * and a club with no keeper on its books at all improvises an outfielder in goal (see
     * {@link #improvisedKeeper}) rather than failing — this method must never leave a fixture
     * unplayable.
     *
     * <p>Outfield selection is <b>slot-aware</b>: the club's default formation
     * (see {@link FormationSlots}) supplies 10 ordered outfield slots and each is filled greedily,
     * natural-first — a slot takes the best remaining player who plays there naturally (any of
     * {@link Player#positions()}, primary or alternate), and only falls back to the closest player
     * by tactical distance when no natural remains. Slots are processed scarcest-first (fewest
     * natural candidates), ties broken by slot index, so a specialist slot is never starved by a
     * generalist grabbing its only candidate. The result is a pure function of the squad +
     * formation, so the two calls made per fixture (parallel pre-sim at kickoff, attribution at
     * finish) always return the identical XI.
     * <p>
     * Delegates to {@link #bestXi(UUID, LocalDate, UUID)} with a null opponent, which imposes no
     * loan restriction.
     */
    public List<Player> bestXi(UUID clubId, LocalDate onDate) {
        return bestXi(clubId, onDate, null);
    }

    /**
     * Same as {@link #bestXi(UUID, LocalDate)}, but also excludes a player who is on loan and
     * barred from facing {@code opponentClubId} (see {@link LoanEligibility}) — such a player is
     * treated exactly like a suspended one, eligible for the ineligible-fallback top-up below when
     * the club has fewer than 10 eligible outfielders.
     */
    public List<Player> bestXi(UUID clubId, LocalDate onDate, UUID opponentClubId) {
        List<Player> allPlayers = playerRepository.findByClubIdAndYouthProspectFalse(clubId);
        List<Player> eligible = allPlayers.stream()
                .filter(Player::isEligible)
                .filter(p -> loanEligibility.mayFace(p, opponentClubId))
                .toList();

        // Ascending comparator: .max(byRating) yields the highest-rated player.
        // Secondary tie-break on id (a deterministic total order) so that rating ties resolve
        // identically across repeated calls regardless of the DB row order
        // (findByClubIdAndYouthProspectFalse has no ORDER BY) — required because this method is
        // called twice for the same fixture (parallel pre-simulation at kickoff, then attribution
        // at finish) and both calls must select the identical XI.
        Comparator<Player> byRating =
                Comparator.comparingDouble((Player p) -> rating(p, onDate))
                        .thenComparing(Player::getId);

        // Try to find an eligible GK; fall back to any GK if none are eligible; improvise an
        // outfielder in goal if the club has no keeper at all.
        Player bestGk = eligible.stream()
                .filter(p -> p.getPosition() == Position.GK)
                .max(byRating)
                .or(() -> allPlayers.stream()
                        .filter(p -> p.getPosition() == Position.GK)
                        .max(byRating))
                .orElseGet(() -> improvisedKeeper(clubId, eligible, allPlayers, byRating));

        // Candidate pool for the 10 outfield slots: prefer eligible, top up from ineligible if the
        // club has fewer than 10 eligible outfielders (graceful fallback, same as before).
        List<Player> pool = eligible.stream()
                .filter(p -> p.getPosition() != Position.GK && !p.getId().equals(bestGk.getId()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (pool.size() < 10) {
            Set<UUID> usedIds = pool.stream().map(Player::getId).collect(Collectors.toCollection(java.util.HashSet::new));
            usedIds.add(bestGk.getId());
            List<Player> ineligibleOutfield = allPlayers.stream()
                    .filter(p -> !usedIds.contains(p.getId()) && p.getPosition() != Position.GK)
                    .sorted(byRating.reversed())
                    .toList();
            int needed = 10 - pool.size();
            pool.addAll(ineligibleOutfield.subList(0, Math.min(needed, ineligibleOutfield.size())));
        }

        List<Player> outfield = fillOutfieldSlots(pool, formationFor(clubId), byRating);

        List<Player> xi = new ArrayList<>(1 + outfield.size());
        xi.add(bestGk);
        xi.addAll(outfield);
        return xi;
    }

    /**
     * The outfielder who goes in goal when a club has no goalkeeper on its books at all — best
     * goalkeeping attribute, ties by rating then id (the same deterministic total order the rest of
     * {@link #bestXi} uses, so repeated calls for one fixture agree). Eligible players first, any
     * player as a last resort.
     *
     * <p>Why this exists: rollover releases EVERY final-year player, managed and AI alike, and no AI
     * renewal pass covers the managed club — so a manager who forgets to renew can start a season
     * with zero keepers. Hard-failing there bricked the save (advance threw 422 forever). The engine
     * already supports a keeper-less XI: {@code validateGkCount} lets a manager improvise an
     * outfielder in goal (spec §5.1), and {@code TeamStrengthCalculator} simply contributes no
     * keeper defense. The AI/default path now matches that instead of being stricter than the
     * manager path.
     */
    private Player improvisedKeeper(UUID clubId, List<Player> eligible, List<Player> allPlayers,
                                    Comparator<Player> byRating) {
        Comparator<Player> byKeeping =
                Comparator.comparingInt(Player::getGoalkeeping).thenComparing(byRating);
        return eligible.stream().max(byKeeping)
                .or(() -> allPlayers.stream().max(byKeeping))
                .orElseThrow(() -> new ApiException(ErrorCode.NO_GK_FOUND,
                        "O clube " + clubId + " não tem nenhum jogador para atuar no gol"));
    }

    /** The formation a club plays when no explicit lineup is set: its default, else 4-4-2. */
    private String formationFor(UUID clubId) {
        return clubRepository.findById(clubId)
                .map(Club::getDefaultFormation)
                .filter(f -> f != null && !f.isBlank())
                .orElse("4-4-2");
    }

    /**
     * Fills the formation's 10 outfield slots greedily from {@code pool} (GK slot excluded),
     * natural-first: slots are processed scarcest-first (fewest NATURAL candidates — any of
     * {@link Player#positions()}, primary or alternate — in the initial pool), ties broken by slot
     * index, so scarce specialist slots pick before generalists can starve them. Each slot takes the
     * best remaining NATURAL candidate by {@code byRating}; a genuine natural always beats a
     * higher-rated improviser. Only when no natural remains does a slot fall back to the closest
     * candidate by tactical distance, ties broken by {@code byRating}. Deterministic: scarcity is
     * computed once over the initial pool, so the fill order and every tie-break are a pure function
     * of squad + slots. Returned in slot order (DEF left→right, then MID, then ATT).
     */
    static List<Player> fillOutfieldSlots(List<Player> pool, String formation, Comparator<Player> byRating) {
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        // slots.get(0) is the GK slot; the 10 outfield slots follow in stable order.
        List<FormationSlots.Slot> outfieldSlots = slots.subList(1, slots.size());

        // Shared greedy best-fit assignment (scarcest slot first, tiered by fit); see SlotAssigner.
        Player[] picked = SlotAssigner.assign(outfieldSlots, pool, byRating);

        List<Player> result = new ArrayList<>(outfieldSlots.size());
        for (Player p : picked) {
            if (p != null) {
                result.add(p);
            }
        }
        return result;
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

        // At most one, not exactly one: spec §5.1 lets the manager improvise an outfielder in goal.
        // Two keepers stays blocked — the engine's defense split assumes a single keeper.
        if (gkCount > 1) {
            throw new ApiException(
                    ErrorCode.INVALID_LINEUP,
                    "A escalação pode ter no máximo 1 goleiro, mas encontrou " + gkCount);
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

    /**
     * Parses and validates each selected player's {@link RoleAssignment} (archetype + optional focus).
     *
     * <p>An absent focus defaults to the archetype's default for the resolved position — see
     * {@link RoleArchetype#defaultFocusFor(DetailedPosition)} — falling back to the archetype's
     * position-independent {@link RoleArchetype#canonicalDefaultFocus()} only when no position can be
     * resolved at all (the defensive empty-candidates edge case below). Validity is measured against
     * the <b>slot</b> when the starter carries an explicit {@code slotIndex} — the manager may
     * improvise any player anywhere (spec §5.1), but the role they play must make sense for the spot
     * on the pitch. Without a slot (legacy requests, bench rows) it is measured against the player's
     * own playable {@link Player#positions()}.
     */
    private Map<UUID, RoleAssignment> validateAndParseAssignments(List<LineupStarter> starters,
                                                                  List<Player> clubPlayers,
                                                                  String formation) {
        Map<UUID, Player> byId = clubPlayers.stream().collect(Collectors.toMap(Player::getId, p -> p));
        List<FormationSlots.Slot> slots = FormationSlots.of(formation);
        Map<UUID, RoleAssignment> resolved = new HashMap<>();
        for (LineupStarter starter : starters) {
            RoleArchetype archetype = parseArchetype(starter.role());
            // player is guaranteed non-null in practice: validatePlayersOwnedByClub runs before this
            // and rejects any starter not in clubPlayers. The guard is defensive only.
            Player player = byId.get(starter.playerId());
            List<DetailedPosition> against = candidatePositions(starter, player, slots);
            RoleFocus focus = against.isEmpty()
                    ? parseFocusOrCanonicalDefault(starter.focus(), archetype)
                    : resolveAndValidateFocus(archetype, starter.focus(), against);
            resolved.put(starter.playerId(), new RoleAssignment(archetype, focus));
        }
        return resolved;
    }

    /**
     * The positions an assignment is judged against: the accepted set of the pinned formation slot
     * when one is given, else every position the player can play. Empty when neither is available.
     */
    private List<DetailedPosition> candidatePositions(LineupStarter starter, Player player,
                                                      List<FormationSlots.Slot> slots) {
        Integer slotIndex = starter.slotIndex();
        if (slotIndex != null && slotIndex >= 0 && slotIndex < slots.size()) {
            return List.copyOf(slots.get(slotIndex).accepted());
        }
        return player == null ? List.of() : player.positions();
    }

    /**
     * The archetype must be valid for at least one candidate position. An absent focus resolves to
     * {@link RoleArchetype#defaultFocusFor(DetailedPosition)} of the first such position (position-
     * specific defaults can differ from archetype to archetype — e.g. PLAYMAKER defaults to ATTACK at
     * MEIA_CENTRAL but BALANCED at MEIA_ARMADOR). An explicit focus must still be valid for at least
     * one playable candidate position, exactly as before.
     */
    private RoleFocus resolveAndValidateFocus(RoleArchetype archetype, String rawFocus,
                                              List<DetailedPosition> candidates) {
        List<DetailedPosition> playable = candidates.stream().filter(archetype::validFor).toList();
        if (playable.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_ROLE,
                    "Função " + archetype.label() + " não é válida para a posição "
                            + candidates.get(0).label());
        }
        if (rawFocus == null || rawFocus.isBlank()) {
            return archetype.defaultFocusFor(playable.get(0));
        }
        RoleFocus focus = parseExplicitFocus(rawFocus);
        boolean focusOk = playable.stream().anyMatch(pos -> archetype.focusesFor(pos).contains(focus));
        if (!focusOk) {
            throw new ApiException(ErrorCode.INVALID_ROLE,
                    "Foco " + focus.label() + " não é válido para a função " + archetype.label());
        }
        return focus;
    }

    private RoleArchetype parseArchetype(String raw) {
        try {
            return RoleArchetype.valueOf(raw);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ApiException(ErrorCode.INVALID_ROLE, "Função desconhecida: " + raw);
        }
    }

    /**
     * A null/blank focus falls back to the archetype's canonical default. Used only for the
     * defensive edge case where no candidate position could be resolved at all — whenever a position
     * is known, {@link #resolveAndValidateFocus} defaults to the position-specific focus instead.
     */
    private RoleFocus parseFocusOrCanonicalDefault(String raw, RoleArchetype archetype) {
        if (raw == null || raw.isBlank()) {
            return archetype.canonicalDefaultFocus();
        }
        return parseExplicitFocus(raw);
    }

    private RoleFocus parseExplicitFocus(String raw) {
        try {
            return RoleFocus.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(ErrorCode.INVALID_ROLE, "Foco desconhecido: " + raw);
        }
    }

    /**
     * Parses and validates the optional personal instruction for each selected player, mirroring
     * {@link #validateAndParseAssignments}: a null/blank value maps to null (no instruction), an unknown
     * value throws, and an instruction on a goalkeeper is rejected. Returns playerId → instruction
     * (null-valued entries mean "no instruction").
     */
    private Map<UUID, PlayerInstruction> validateAndParseInstructions(List<LineupStarter> starters, List<Player> clubPlayers) {
        Map<UUID, Player> byId = clubPlayers.stream().collect(Collectors.toMap(Player::getId, p -> p));
        Map<UUID, PlayerInstruction> resolved = new HashMap<>();
        for (LineupStarter starter : starters) {
            PlayerInstruction instruction = parseInstruction(starter.instruction());
            // player is guaranteed non-null in practice (validatePlayersOwnedByClub ran first);
            // the guard is defensive only, matching validateAndParseAssignments.
            Player player = byId.get(starter.playerId());
            if (player != null) {
                validateInstructionForPlayer(player, instruction);
            }
            resolved.put(starter.playerId(), instruction);
        }
        return resolved;
    }

    /**
     * Validates the optional explicit slot assignment: either every starter carries a slotIndex
     * (the 11 values must then be a permutation of 0..10) or none does (legacy greedy assignment).
     * Bench entries never carry one. Runs before any delete/insert so a bad request rejects the
     * whole call without partial mutation.
     *
     * <p>Deliberately does NOT require a goalkeeper at slot 0: spec §5.1 lets the manager improvise
     * anyone anywhere, including in goal. The engine degrades that naturally (no {@code gkDefense}
     * and a x0.50 position-fit penalty on the improviser).
     */
    private void validateSlotIndexes(List<LineupStarter> starters, List<LineupStarter> bench) {
        if (bench != null && bench.stream().anyMatch(s -> s.slotIndex() != null)) {
            throw new ApiException(ErrorCode.INVALID_LINEUP, "Bench players cannot carry a slotIndex");
        }
        long withSlot = starters.stream().filter(s -> s.slotIndex() != null).count();
        if (withSlot == 0) {
            return; // legacy request — the engine falls back to greedy assignment
        }
        if (withSlot != starters.size()) {
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "Either every starter carries a slotIndex or none does");
        }
        Set<Integer> indexes = starters.stream().map(LineupStarter::slotIndex).collect(Collectors.toSet());
        if (indexes.size() != starters.size()
                || indexes.stream().anyMatch(i -> i < 0 || i >= starters.size())) {
            throw new ApiException(ErrorCode.INVALID_LINEUP,
                    "Starter slotIndexes must be a permutation of 0.." + (starters.size() - 1));
        }
    }

    /**
     * Maps a persisted lineup row back to the DTO, round-tripping archetype + focus + optional
     * instruction + optional slot index.
     */
    private LineupStarter toLineupStarter(LineupPlayer lp) {
        return new LineupStarter(
                lp.getPlayerId(),
                lp.getArchetype().name(),
                lp.getFocus().name(),
                lp.getInstruction() == null ? null : lp.getInstruction().name(),
                lp.getSlotIndex());
    }

    private Map<UUID, RoleAssignment> currentRoles(UUID saveGameId, UUID clubId) {
        Map<UUID, RoleAssignment> map = new HashMap<>();
        lineupRepository.findBySaveGameIdAndClubId(saveGameId, clubId).ifPresent(lineup ->
                lineupPlayerRepository.findByLineupId(lineup.getId()).forEach(lp ->
                        map.put(lp.getPlayerId(), new RoleAssignment(lp.getArchetype(), lp.getFocus()))));
        return map;
    }

    /** Familiar roles as their raw CSV tokens (e.g. {@code WINGBACK++}), insertion-ordered. */
    private static List<String> familiarRoleTokens(Player p) {
        return p.familiarRoles().entrySet().stream()
                .map(e -> e.getKey().name() + (e.getValue() == 2 ? "++" : "+"))
                .toList();
    }

    private PlayerResponse toPlayerResponse(Player p, RoleAssignment role, LocalDate onDate) {
        int fitness = fitnessCalculator.effectiveFitness(p.getFitness(), p.getLastMatchDate(), onDate, p.getAge());
        List<AttributeView> attributes = java.util.Arrays.stream(Attribute.values())
                .map(a -> new AttributeView(a.name(), a.label(), a.group().name(), p.attribute(a)))
                .toList();
        return new PlayerResponse(
                p.getId(),
                p.getName(),
                p.getPosition().name(),
                p.getDetailedPosition() != null ? p.getDetailedPosition().name() : null,
                p.getDetailedPosition() != null ? p.getDetailedPosition().label() : null,
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
                role != null ? role.archetype().name() : null,
                role != null ? role.focus().name() : null,
                p.getAltPositions().stream().map(Enum::name).toList(),
                familiarRoleTokens(p),
                p.getContractYears(),
                transferValuation.marketValue(p.getOverall(), p.getAge()),
                contractValuation.wageDemand(p.getOverall(), p.getAge()),
                contractValuation.terminationFee(p.getOverall(), p.getAge(), p.getContractYears()),
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

    /**
     * Parses an optional per-player instruction: a null/blank value means "no instruction" (returns
     * null, the neutral no-op), a valid enum name returns the instruction, anything else is a client
     * validation error. Package-private and unit-testable; wired to the lineup DTO by Task 28.
     */
    PlayerInstruction parseInstruction(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return PlayerInstruction.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Instrução pessoal inválida: " + v);
        }
    }

    /**
     * Rejects a personal instruction on a goalkeeper (keepers have no attacking/defensive instruction
     * hooks). A null instruction, or any instruction on an outfield player, is accepted. Uses the same
     * 422 validation code as the sibling tactics validators. Package-private and unit-testable; wired
     * to the lineup DTO by Task 28.
     */
    void validateInstructionForPlayer(Player p, PlayerInstruction instr) {
        if (instr != null && p.getPosition() == Position.GK) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Goleiros não recebem instruções pessoais");
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
