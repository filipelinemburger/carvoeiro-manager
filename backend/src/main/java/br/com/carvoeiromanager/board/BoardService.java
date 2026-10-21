package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.board.BoardConfidenceCalculator.MatchInput;
import br.com.carvoeiromanager.board.BoardConfidenceCalculator.Part;
import br.com.carvoeiromanager.board.BoardRules.Verdict;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Competition;
import br.com.carvoeiromanager.competition.CompetitionClub;
import br.com.carvoeiromanager.competition.CompetitionClubRepository;
import br.com.carvoeiromanager.competition.CompetitionPhase;
import br.com.carvoeiromanager.competition.CompetitionPhaseRepository;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.CompetitionScope;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.KnockoutTie;
import br.com.carvoeiromanager.competition.PhaseKind;
import br.com.carvoeiromanager.competition.PhaseResolver;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsService;
import br.com.carvoeiromanager.competition.TieResolver;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The board (<em>a diretoria</em>): sets each season's objectives, judges every managed match,
 * warns, and sacks (spec 2026-10-21). The arithmetic lives in the pure classes beside it
 * ({@link BoardConfidenceCalculator}, {@link BoardRules}, {@link ObjectiveTargets}); this class
 * only gathers their inputs and persists what they decide.
 *
 * <p>Called from exactly four places: career creation ({@link #initialize}), every managed match
 * round ({@link #afterManagedMatch}), the season's end ({@link #reviewSeason}) and rollover
 * ({@link #startNewSeason}). Each runs inside its caller's transaction, so a round that rolls back
 * takes its confidence change — and any sacking — with it.
 */
@Service
public class BoardService {

    /** Most recent changes shown on the card. */
    static final int RECENT_CHANGES = 8;

    private final BoardStateRepository stateRepository;
    private final BoardObjectiveRepository objectiveRepository;
    private final BoardConfidenceChangeRepository changeRepository;
    private final BoardNewsWriter newsWriter;
    private final CompetitionRepository competitionRepository;
    private final CompetitionClubRepository competitionClubRepository;
    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final FixtureRepository fixtureRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final StandingsService standingsService;
    private final PhaseResolver phaseResolver;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final ObjectMapper objectMapper;

    public BoardService(BoardStateRepository stateRepository,
                        BoardObjectiveRepository objectiveRepository,
                        BoardConfidenceChangeRepository changeRepository,
                        BoardNewsWriter newsWriter,
                        CompetitionRepository competitionRepository,
                        CompetitionClubRepository competitionClubRepository,
                        CompetitionPhaseRepository competitionPhaseRepository,
                        FixtureRepository fixtureRepository,
                        SeasonEventRepository seasonEventRepository,
                        StandingsService standingsService,
                        PhaseResolver phaseResolver,
                        ClubRepository clubRepository,
                        PlayerRepository playerRepository,
                        ObjectMapper objectMapper) {
        this.stateRepository = stateRepository;
        this.objectiveRepository = objectiveRepository;
        this.changeRepository = changeRepository;
        this.newsWriter = newsWriter;
        this.competitionRepository = competitionRepository;
        this.competitionClubRepository = competitionClubRepository;
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.fixtureRepository = fixtureRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.standingsService = standingsService;
        this.phaseResolver = phaseResolver;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.objectMapper = objectMapper;
    }

    // ---------------------------------------------------------------------------------------------
    // State and objectives
    // ---------------------------------------------------------------------------------------------

    /** A new career: confidence at the start value, no grace season, and this season's objectives. */
    @Transactional
    public void initialize(SaveGame save, boolean sackingEnabled) {
        stateRepository.save(BoardState.create(save.getId(), BoardConfidenceCalculator.START, sackingEnabled, null));
        setSeasonObjectives(save);
    }

    /**
     * The board's state, created on first touch for a career that predates the board (spec §7.5).
     * Such a career gets a grace season — its manager made this season's decisions without knowing
     * a board existed — and objectives computed from today's squads. A state objective whose run is
     * already over is created already resolved, with no confidence change: no retroactive punishment.
     */
    @Transactional
    public BoardState ensureState(SaveGame save) {
        Optional<BoardState> existing = stateRepository.findById(save.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        BoardState state = stateRepository.save(BoardState.create(save.getId(), BoardConfidenceCalculator.START,
                true, save.getSeasonYear()));
        if (objectiveRepository.findBySaveGameIdAndSeasonYear(save.getId(), save.getSeasonYear()).isEmpty()) {
            List<BoardObjective> objectives = writeObjectives(save);
            for (BoardObjective objective : objectives) {
                if (objective.getTargetStage() == null || objective.getCompetitionId() == null) {
                    continue;
                }
                StageStatus status = stageStatus(save, competition(objective.getCompetitionId()), null);
                Optional<Boolean> met = stageVerdict(status, objective.getTargetStage());
                met.ifPresent(m -> objective.resolve(m ? ObjectiveOutcome.MET : ObjectiveOutcome.FAILED));
            }
            objectiveRepository.saveAll(objectives);
            postObjectivesNews(save, objectives);
        }
        return state;
    }

    /**
     * Writes this season's objectives (up to three — a season lacking a league or a state
     * championship simply has no objective for it) and posts the {@code BOARD_OBJECTIVES} item.
     */
    @Transactional
    public void setSeasonObjectives(SaveGame save) {
        List<BoardObjective> objectives = writeObjectives(save);
        postObjectivesNews(save, objectives);
    }

    private List<BoardObjective> writeObjectives(SaveGame save) {
        UUID managedClubId = save.getManagedClubId();
        List<BoardObjective> objectives = new ArrayList<>();
        nationalCompetition(save).ifPresent(league -> {
            int rank = expectedRank(save, league, managedClubId);
            int field = members(league).size();
            LeagueTarget target = ObjectiveTargets.league(league.getTier(), rank, field);
            if (target.stage() != null) {
                // Fail at objective-setting time, not mid-season, if the format has no stage names.
                knockoutPhases(league);
            }
            objectives.add(BoardObjective.create(save.getId(), save.getSeasonYear(), ObjectiveKind.LEAGUE,
                    league.getId(), league.getTier(), target.position(), target.stage()));
        });
        stateCompetition(save).ifPresent(state -> {
            knockoutPhases(state);
            int rank = expectedRank(save, state, managedClubId);
            Stage target = ObjectiveTargets.state(rank, members(state).size());
            objectives.add(BoardObjective.create(save.getId(), save.getSeasonYear(), ObjectiveKind.STATE,
                    state.getId(), null, null, target));
        });
        objectives.add(BoardObjective.create(save.getId(), save.getSeasonYear(), ObjectiveKind.FINANCE,
                null, null, null, null));
        return objectiveRepository.saveAll(objectives);
    }

    private void postObjectivesNews(SaveGame save, List<BoardObjective> objectives) {
        int confidence = stateRepository.findById(save.getId()).map(BoardState::getConfidence)
                .orElse(BoardConfidenceCalculator.START);
        newsWriter.write(save.getId(), seasonStart(save), "BOARD_OBJECTIVES",
                new BoardEventResponse(confidence, 0, false, false, false, false, toResponses(save, objectives)));
    }

    /** The season's first calendar day, so the objectives item sits at the top of a new feed. */
    private LocalDate seasonStart(SaveGame save) {
        return seasonEventRepository.findBySaveGameId(save.getId()).stream()
                .map(SeasonEvent::getEventDate)
                .min(LocalDate::compareTo)
                .orElse(save.getCurrentDate());
    }

    // ---------------------------------------------------------------------------------------------
    // After every managed match
    // ---------------------------------------------------------------------------------------------

    /**
     * Judges one managed round. Returns null when the managed club has no fixture in it — the
     * rounds of a competition it has already been knocked out of, which {@code advance} keeps
     * popping: those move nothing, and do not count toward the ultimatum's three matches.
     *
     * <p>Runs after {@code PhaseResolver.resolveIfPhaseComplete} (a knockout exit or a title is
     * only knowable then) and before the season-finished check, so a sacking on the last round is
     * already on the save when that check runs.
     */
    @Transactional
    public BoardEventResponse afterManagedMatch(SaveGame save, SeasonEvent event, List<Fixture> fixtures) {
        UUID managedClubId = save.getManagedClubId();
        Fixture fixture = fixtures.stream()
                .filter(f -> managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()))
                .filter(Fixture::isPlayed)
                .findFirst().orElse(null);
        if (fixture == null) {
            return null;
        }
        BoardState state = ensureState(save);
        LocalDate date = event.getEventDate();
        Competition competition = competition(event.getCompetitionId());
        List<BoardObjective> objectives =
                objectiveRepository.findBySaveGameIdAndSeasonYear(save.getId(), save.getSeasonYear());
        int before = state.getConfidence();

        // 1. The match itself.
        boolean home = managedClubId.equals(fixture.getHomeClubId());
        UUID opponentId = home ? fixture.getAwayClubId() : fixture.getHomeClubId();
        int goalsFor = home ? fixture.getHomeGoals() : fixture.getAwayGoals();
        int goalsAgainst = home ? fixture.getAwayGoals() : fixture.getHomeGoals();
        Integer position = null;
        Integer reference = null;
        if (competition.getScope() == CompetitionScope.NATIONAL) {
            BoardObjective league = find(objectives, ObjectiveKind.LEAGUE).orElse(null);
            if (league != null && leagueMatchesPlayed(save, competition) >= TRAJECTORY_FROM_MATCH) {
                LivePosition live = livePosition(save, competition, league, event.getId());
                if (live != null) {
                    position = live.position();
                    reference = live.reference();
                }
            }
        }
        boolean negativeBalance = club(managedClubId).getBalance().signum() < 0;
        MatchInput input = new MatchInput(strength(managedClubId), strength(opponentId), home,
                goalsFor, goalsAgainst, competition.getScope() == CompetitionScope.STATE,
                position, reference, negativeBalance);
        String opponentName = clubName(opponentId);
        for (Part part : BoardConfidenceCalculator.afterMatch(input)) {
            Map<String, Object> payload = switch (part.reason()) {
                case MATCH_RESULT, HEAVY_DEFEAT -> matchPayload(opponentName, goalsFor, goalsAgainst, home);
                case TABLE_POSITION -> Map.of("position", Objects.requireNonNull(position));
                default -> null;
            };
            record(save, state, date, part.reason(), part.delta(), payload);
        }

        // 2. A phase of this competition just ended: settle any stage objective that is now decided.
        resolvePhaseObjectives(save, state, event, competition, objectives);

        // 3. Warnings and sacking. The counter ticks before the rules look at it, so the match that
        //    issues an ultimatum leaves it at 0 and the third match after it is the first that can sack.
        state.countMatchSinceUltimatum();
        Verdict verdict = BoardRules.afterMatch(state.getConfidence(), state.isUltimatumActive(),
                state.getMatchesSinceUltimatum(), nationalMatchesPlayed(save), state.canSack(save.getSeasonYear()));
        BoardEventResponse response = applyVerdict(save, state, date, verdict, before, List.of());
        stateRepository.save(state);
        return response;
    }

    /** The trajectory rule only starts once the table means something. */
    static final int TRAJECTORY_FROM_MATCH = 7;

    private record LivePosition(int position, int reference) { }

    /**
     * The managed club's place in the league's running round-robin table and the place it is
     * measured against: the target position, or for a stage target the qualifying line (the
     * round-robin phase's {@code qualifiers}). Null once a phased league's round robin is over —
     * a knockout has no table — or when the round robin is complete with this very round.
     */
    private LivePosition livePosition(SaveGame save, Competition league, BoardObjective objective, UUID justProcessedId) {
        List<CompetitionPhase> phases = phases(league);
        UUID phaseId = null;
        Integer qualifiers = null;
        if (!phases.isEmpty()) {
            CompetitionPhase roundRobin = phases.get(0);
            if (phaseResolver.isPhaseComplete(save.getId(), roundRobin, justProcessedId)) {
                return null;
            }
            phaseId = roundRobin.getId();
            qualifiers = roundRobin.getQualifiers();
        }
        Integer reference = objective.getTargetPosition() != null ? objective.getTargetPosition() : qualifiers;
        if (reference == null) {
            return null;
        }
        int position = positionOf(standingsService.computeStandings(save, league.getId(), phaseId, null),
                save.getManagedClubId());
        return position < 0 ? null : new LivePosition(position, reference);
    }

    private void resolvePhaseObjectives(SaveGame save, BoardState state, SeasonEvent event, Competition competition,
                                        List<BoardObjective> objectives) {
        if (event.getPhaseId() == null) {
            return;
        }
        CompetitionPhase phase = competitionPhaseRepository.findById(event.getPhaseId()).orElse(null);
        if (phase == null || !phaseResolver.isPhaseComplete(save.getId(), phase, event.getId())) {
            return;
        }
        LocalDate date = event.getEventDate();
        StageStatus status = stageStatus(save, competition, event.getId());
        for (BoardObjective objective : objectives) {
            if (!objective.isPending() || !competition.getId().equals(objective.getCompetitionId())) {
                continue;
            }
            if (objective.getTargetStage() != null) {
                stageVerdict(status, objective.getTargetStage())
                        .ifPresent(met -> resolveStage(save, state, date, objective, status.reached(), met));
            } else if (objective.getKind() == ObjectiveKind.LEAGUE && phase.getKind() == PhaseKind.ROUND_ROBIN) {
                // A phased league's position target is read against its round-robin table, which is
                // final now (spec §5.5).
                int finalPosition = positionOf(
                        standingsService.computeStandings(save, competition.getId(), phase.getId(), null),
                        save.getManagedClubId());
                resolveLeaguePosition(save, state, date, objective, competition, finalPosition);
            }
        }
        objectiveRepository.saveAll(objectives);

        // The title: the final just ended and the managed club won it.
        List<CompetitionPhase> knockouts = knockoutPhases(competition);
        boolean finalPhase = !knockouts.isEmpty() && knockouts.getLast().getId().equals(phase.getId());
        if (finalPhase && status.reached() == Stage.CHAMPION) {
            record(save, state, date, BoardChangeReason.TITLE_WON, BoardConfidenceCalculator.TITLE_WON,
                    Map.of("competition", competition.getName()));
        }
    }

    private void resolveStage(SaveGame save, BoardState state, LocalDate date, BoardObjective objective,
                              Stage reached, boolean met) {
        objective.resolve(met ? ObjectiveOutcome.MET : ObjectiveOutcome.FAILED);
        Map<String, Object> payload = Map.of("stage", reached.name());
        if (objective.getKind() == ObjectiveKind.STATE) {
            record(save, state, date, met ? BoardChangeReason.STATE_MET : BoardChangeReason.STATE_FAILED,
                    BoardConfidenceCalculator.stateDelta(met), payload);
        } else {
            record(save, state, date, met ? BoardChangeReason.LEAGUE_MET : BoardChangeReason.LEAGUE_FAILED,
                    BoardConfidenceCalculator.leagueStageDelta(reached, objective.getTargetStage()), payload);
        }
    }

    private void resolveLeaguePosition(SaveGame save, BoardState state, LocalDate date, BoardObjective objective,
                                       Competition league, int finalPosition) {
        boolean met = finalPosition <= objective.getTargetPosition();
        objective.resolve(met ? ObjectiveOutcome.MET : ObjectiveOutcome.FAILED);
        // Only Série A and B have a relegation zone; Série C has none (there is no Série D).
        boolean relegationZone = league.getTier() != null && league.getTier() <= 2 && phases(league).isEmpty();
        int delta = BoardConfidenceCalculator.leaguePositionDelta(finalPosition, objective.getTargetPosition(),
                relegationZone, members(league).size());
        record(save, state, date, met ? BoardChangeReason.LEAGUE_MET : BoardChangeReason.LEAGUE_FAILED, delta,
                Map.of("position", finalPosition));
    }

    // ---------------------------------------------------------------------------------------------
    // Season end and rollover
    // ---------------------------------------------------------------------------------------------

    /**
     * The season-end review (spec §7.2). Must run AFTER the season-end prize is paid — the finance
     * objective judges the balance with the prize in it — and decides the final status: FINISHED,
     * or SACKED. A review that resolves nothing new (it already ran) still decides the status.
     */
    @Transactional
    public BoardEventResponse reviewSeason(SaveGame save) {
        BoardState state = ensureState(save);
        int before = state.getConfidence();
        LocalDate date = save.getCurrentDate();
        List<BoardObjective> objectives =
                objectiveRepository.findBySaveGameIdAndSeasonYear(save.getId(), save.getSeasonYear());
        for (BoardObjective objective : objectives) {
            if (!objective.isPending()) {
                continue;
            }
            switch (objective.getKind()) {
                case FINANCE -> {
                    boolean met = club(save.getManagedClubId()).getBalance().signum() > 0;
                    objective.resolve(met ? ObjectiveOutcome.MET : ObjectiveOutcome.FAILED);
                    record(save, state, date, met ? BoardChangeReason.FINANCE_MET : BoardChangeReason.FINANCE_FAILED,
                            BoardConfidenceCalculator.financeDelta(met), null);
                }
                case LEAGUE, STATE -> {
                    Competition competition = competition(objective.getCompetitionId());
                    if (objective.getTargetStage() != null) {
                        StageStatus status = stageStatus(save, competition, null);
                        // The season is over: whatever was reached is final.
                        boolean met = status.reached() != null
                                && status.reached().ordinal() >= objective.getTargetStage().ordinal();
                        resolveStage(save, state, date, objective,
                                status.reached() == null ? Stage.NOT_QUALIFIED : status.reached(), met);
                    } else {
                        List<CompetitionPhase> phases = phases(competition);
                        UUID phaseId = phases.isEmpty() ? null : phases.get(0).getId();
                        int finalPosition = positionOf(
                                standingsService.computeStandings(save, competition.getId(), phaseId, null),
                                save.getManagedClubId());
                        resolveLeaguePosition(save, state, date, objective, competition, finalPosition);
                    }
                }
            }
        }
        objectiveRepository.saveAll(objectives);

        List<BoardObjectiveResponse> summary = toResponses(save, objectives);
        newsWriter.write(save.getId(), date, "BOARD_REVIEW", new BoardEventResponse(state.getConfidence(),
                state.getConfidence() - before, false, false, false, false, summary));

        Verdict verdict = BoardRules.atSeasonEnd(state.getConfidence(), state.canSack(save.getSeasonYear()));
        // FINISHED unless the board sacks: an averted sacking still ends the season normally.
        if (verdict != Verdict.SACK) {
            save.setStatus(SaveGameStatus.FINISHED);
        }
        BoardEventResponse response = applyVerdict(save, state, date, verdict, before, summary);
        stateRepository.save(state);
        return response;
    }

    /**
     * The new season (spec §6.3): confidence moves halfway back toward the start value, the
     * finished season's change log is purged, any ultimatum is lifted, and new objectives are set.
     * The caller runs this after the news purge (so the objectives item survives) and after the
     * new calendar exists (so the item can be dated on its first day).
     */
    @Transactional
    public void startNewSeason(SaveGame save) {
        BoardState state = ensureState(save);
        changeRepository.deleteBySaveGameId(save.getId());
        int before = state.getConfidence();
        int after = BoardConfidenceCalculator.regress(before);
        state.liftUltimatum();
        state.setConfidence(after);
        changeRepository.save(BoardConfidenceChange.create(save.getId(), seasonStart(save),
                changeRepository.countBySaveGameId(save.getId()) + 1, BoardChangeReason.NEW_SEASON,
                after - before, after, null));
        stateRepository.save(state);
        if (objectiveRepository.findBySaveGameIdAndSeasonYear(save.getId(), save.getSeasonYear()).isEmpty()) {
            setSeasonObjectives(save);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Verdicts
    // ---------------------------------------------------------------------------------------------

    private BoardEventResponse applyVerdict(SaveGame save, BoardState state, LocalDate date, Verdict verdict,
                                            int before, List<BoardObjectiveResponse> objectives) {
        boolean issued = false;
        boolean lifted = false;
        boolean sacked = false;
        boolean averted = false;
        switch (verdict) {
            case ISSUE_ULTIMATUM -> {
                state.issueUltimatum();
                issued = true;
            }
            case LIFT_ULTIMATUM -> {
                state.liftUltimatum();
                lifted = true;
            }
            case SACK -> {
                save.setStatus(SaveGameStatus.SACKED);
                sacked = true;
            }
            case SACKING_AVERTED -> {
                // Raised to the floor so the displeasure stays visible without repeating every match.
                int raise = Math.max(0, BoardRules.AVERTED_FLOOR - state.getConfidence());
                record(save, state, date, BoardChangeReason.SACKING_AVERTED, raise, null);
                averted = true;
            }
            case NONE -> { }
        }
        BoardEventResponse response = new BoardEventResponse(state.getConfidence(), state.getConfidence() - before,
                issued, lifted, sacked, averted, objectives);
        String newsType = switch (verdict) {
            case ISSUE_ULTIMATUM -> "BOARD_ULTIMATUM";
            case LIFT_ULTIMATUM -> "BOARD_ULTIMATUM_LIFTED";
            case SACK -> "BOARD_SACKED";
            case SACKING_AVERTED -> "BOARD_SACKING_AVERTED";
            case NONE -> null;
        };
        if (newsType != null) {
            newsWriter.write(save.getId(), date, newsType, response);
        }
        return response;
    }

    /** Applies one change to the state and writes its row. A zero delta (e.g. no raise) is skipped. */
    private void record(SaveGame save, BoardState state, LocalDate date, BoardChangeReason reason, int delta,
                        Map<String, Object> payload) {
        int beforeClamp = state.getConfidence();
        state.setConfidence(beforeClamp + delta);
        int applied = state.getConfidence() - beforeClamp;
        if (applied == 0 && delta == 0) {
            return;
        }
        String json = payload == null ? null : objectMapper.writeValueAsString(payload);
        changeRepository.save(BoardConfidenceChange.create(save.getId(), date,
                changeRepository.countBySaveGameId(save.getId()) + 1, reason, applied, state.getConfidence(), json));
    }

    private static Map<String, Object> matchPayload(String opponent, int goalsFor, int goalsAgainst, boolean home) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("opponent", opponent);
        payload.put("score", goalsFor + "–" + goalsAgainst);
        payload.put("home", home);
        return payload;
    }

    // ---------------------------------------------------------------------------------------------
    // Read side
    // ---------------------------------------------------------------------------------------------

    /** The card's view. The caller has already checked ownership. Allowed on a sacked save. */
    @Transactional
    public BoardResponse getBoard(SaveGame save) {
        BoardState state = ensureState(save);
        List<BoardObjective> objectives =
                objectiveRepository.findBySaveGameIdAndSeasonYear(save.getId(), save.getSeasonYear());
        List<BoardChangeResponse> changes = changeRepository.findTop8BySaveGameIdOrderBySeqDesc(save.getId()).stream()
                .map(c -> new BoardChangeResponse(c.getEventDate().toString(), c.getReason().name(), c.getDelta(),
                        c.getPayload() == null ? null
                                : objectMapper.readValue(c.getPayload(), new TypeReference<Map<String, Object>>() { })))
                .toList();
        return new BoardResponse(state.getConfidence(), state.isUltimatumActive(), state.isSackingEnabled(),
                state.isGraceSeason(save.getSeasonYear()), toResponses(save, objectives), changes);
    }

    /** Every season's objectives, oldest first — the career record the sacked screen shows. */
    @Transactional
    public List<BoardSeasonRecord> history(SaveGame save) {
        ensureState(save);
        Map<Integer, List<BoardObjective>> bySeason = new java.util.TreeMap<>();
        for (BoardObjective o : objectiveRepository.findBySaveGameIdOrderBySeasonYearAsc(save.getId())) {
            bySeason.computeIfAbsent(o.getSeasonYear(), y -> new ArrayList<>()).add(o);
        }
        return bySeason.entrySet().stream()
                .map(e -> new BoardSeasonRecord(e.getKey(), toResponses(save, e.getValue())))
                .toList();
    }

    private List<BoardObjectiveResponse> toResponses(SaveGame save, List<BoardObjective> objectives) {
        List<BoardObjectiveResponse> out = new ArrayList<>();
        for (BoardObjective o : sortByKind(objectives)) {
            Competition competition = o.getCompetitionId() == null ? null
                    : competitionRepository.findById(o.getCompetitionId()).orElse(null);
            Integer current = null;
            String progress = null;
            if (o.isPending()) {
                if (o.getKind() == ObjectiveKind.LEAGUE && competition != null
                        && leagueMatchesPlayed(save, competition) > 0) {
                    LivePosition live = livePosition(save, competition, o, null);
                    if (live != null) {
                        current = live.position();
                        int gap = live.position() - live.reference();
                        progress = gap <= 0 ? "ON_TRACK" : gap <= 3 ? "AT_RISK" : "OFF_TRACK";
                    }
                } else if (o.getKind() == ObjectiveKind.FINANCE) {
                    progress = club(save.getManagedClubId()).getBalance().signum() > 0 ? "ON_TRACK" : "OFF_TRACK";
                }
            }
            out.add(new BoardObjectiveResponse(o.getKind().name(), competition == null ? null : competition.getName(),
                    o.getTier(), o.getTargetPosition(), o.getTargetStage() == null ? null : o.getTargetStage().name(),
                    current, progress, o.getOutcome().name()));
        }
        return out;
    }

    private static List<BoardObjective> sortByKind(List<BoardObjective> objectives) {
        return objectives.stream().sorted(java.util.Comparator.comparing(BoardObjective::getKind)).toList();
    }

    // ---------------------------------------------------------------------------------------------
    // Stages
    // ---------------------------------------------------------------------------------------------

    /**
     * How far the managed club has got in a phased competition. {@code reached} is null while the
     * round robin is still running; {@code finished} is true once the run is over (out, or champion).
     */
    record StageStatus(Stage reached, boolean finished) { }

    /** MET/FAILED once decided, empty while the run could still reach the target. */
    private static Optional<Boolean> stageVerdict(StageStatus status, Stage target) {
        if (status.reached() != null && status.reached().ordinal() >= target.ordinal()) {
            return Optional.of(true);
        }
        if (status.finished()) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    /**
     * Walks the phases in order. A stage is reached by winning the phase before it (spec §5.3):
     * qualifying from the round robin reaches the first knockout stage, and winning the final is
     * CHAMPION. {@code justProcessedId} is the event being recorded (not yet marked processed).
     */
    StageStatus stageStatus(SaveGame save, Competition competition, UUID justProcessedId) {
        UUID managedClubId = save.getManagedClubId();
        List<CompetitionPhase> phases = phases(competition);
        if (phases.isEmpty()) {
            return new StageStatus(null, false);
        }
        CompetitionPhase roundRobin = phases.getFirst();
        if (!phaseResolver.isPhaseComplete(save.getId(), roundRobin, justProcessedId)) {
            return new StageStatus(null, false);
        }
        int index = positionOf(standingsService.computeStandings(save, competition.getId(), roundRobin.getId(), null),
                managedClubId) - 1;
        List<CompetitionPhase> knockouts = knockoutPhases(competition);
        if (index < 0 || roundRobin.getQualifiers() == null || index >= roundRobin.getQualifiers()
                || knockouts.isEmpty()) {
            return new StageStatus(Stage.NOT_QUALIFIED, true);
        }
        Stage reached = StageLadder.stageOfKnockout(0, knockouts.size());
        for (int i = 0; i < knockouts.size(); i++) {
            CompetitionPhase phase = knockouts.get(i);
            if (!phaseResolver.isPhaseComplete(save.getId(), phase, justProcessedId)) {
                return new StageStatus(reached, false);
            }
            KnockoutTie tie = TieResolver.tiesOf(fixtureRepository.findBySaveGameIdAndPhaseId(save.getId(), phase.getId()))
                    .stream()
                    .filter(t -> managedClubId.equals(t.higherSeedClubId()) || managedClubId.equals(t.lowerSeedClubId()))
                    .findFirst().orElse(null);
            if (tie == null || !managedClubId.equals(TieResolver.winner(tie))) {
                return new StageStatus(reached, true);
            }
            reached = StageLadder.stageAfterWinning(i, knockouts.size());
        }
        return new StageStatus(reached, true);
    }

    /** The knockout phases after the round robin. Throws for a format {@link Stage} cannot name. */
    private List<CompetitionPhase> knockoutPhases(Competition competition) {
        List<CompetitionPhase> knockouts = phases(competition).stream()
                .filter(p -> p.getKind() == PhaseKind.KNOCKOUT).toList();
        if (!knockouts.isEmpty()) {
            StageLadder.stageOfKnockout(0, knockouts.size());
        }
        return knockouts;
    }

    private List<CompetitionPhase> phases(Competition competition) {
        return competitionPhaseRepository.findByCompetitionIdOrderByOrdinalAsc(competition.getId());
    }

    // ---------------------------------------------------------------------------------------------
    // Lookups
    // ---------------------------------------------------------------------------------------------

    private Optional<Competition> nationalCompetition(SaveGame save) {
        return managedCompetitions(save).stream()
                .filter(c -> c.getScope() == CompetitionScope.NATIONAL && c.getTier() != null).findFirst();
    }

    private Optional<Competition> stateCompetition(SaveGame save) {
        return managedCompetitions(save).stream()
                .filter(c -> c.getScope() == CompetitionScope.STATE).findFirst();
    }

    /** Competitions the managed club is a member of — membership, never a stored flag. */
    private List<Competition> managedCompetitions(SaveGame save) {
        return competitionClubRepository.findBySaveGameIdAndClubId(save.getId(), save.getManagedClubId()).stream()
                .map(CompetitionClub::getCompetitionId)
                .distinct()
                .map(competitionRepository::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    private List<UUID> members(Competition competition) {
        return competitionClubRepository.findByCompetitionId(competition.getId()).stream()
                .map(CompetitionClub::getClubId).toList();
    }

    /**
     * One squad query per member: at most 20, three times a season, so not worth a bespoke
     * aggregate query.
     */
    private int expectedRank(SaveGame save, Competition competition, UUID managedClubId) {
        Map<UUID, Double> strength = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        for (UUID clubId : members(competition)) {
            strength.put(clubId, strength(clubId));
            names.put(clubId, clubName(clubId));
        }
        return ObjectiveTargets.expectedRank(managedClubId, strength, names);
    }

    private double strength(UUID clubId) {
        return ClubStrength.of(playerRepository.findByClubIdAndYouthProspectFalse(clubId));
    }

    private int leagueMatchesPlayed(SaveGame save, Competition league) {
        UUID managedClubId = save.getManagedClubId();
        return (int) fixtureRepository.findBySaveGameIdAndCompetitionId(save.getId(), league.getId()).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()))
                .count();
    }

    private int nationalMatchesPlayed(SaveGame save) {
        return nationalCompetition(save).map(league -> leagueMatchesPlayed(save, league)).orElse(0);
    }

    /** 1-based position of the club in the table, or −1 if it is not in it. */
    private static int positionOf(List<StandingRow> table, UUID clubId) {
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).clubId().equals(clubId)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static Optional<BoardObjective> find(List<BoardObjective> objectives, ObjectiveKind kind) {
        return objectives.stream().filter(o -> o.getKind() == kind).findFirst();
    }

    private Competition competition(UUID competitionId) {
        return competitionRepository.findById(competitionId)
                .orElseThrow(() -> new IllegalStateException("Competition " + competitionId + " has no row"));
    }

    private Club club(UUID clubId) {
        return clubRepository.findById(clubId)
                .orElseThrow(() -> new IllegalStateException("Club " + clubId + " has no row"));
    }

    private String clubName(UUID clubId) {
        return clubRepository.findById(clubId)
                .map(c -> c.getCommonName() != null ? c.getCommonName() : c.getName())
                .orElse("");
    }
}
