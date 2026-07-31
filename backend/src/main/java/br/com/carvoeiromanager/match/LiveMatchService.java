package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.common.ErrorCode;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.finance.AttendanceCalculator;
import br.com.carvoeiromanager.player.AttackZone;
import br.com.carvoeiromanager.player.Formation;
import br.com.carvoeiromanager.player.GameStyle;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.RoleAssignment;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LiveMatchService {

    private static final Logger log = LoggerFactory.getLogger(LiveMatchService.class);

    static final int MAX_SUBS = 5;
    static final int MINUTES = 90;

    private final SaveGameService saveGameService;
    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final FitnessCalculator fitnessCalculator;
    private final LiveMatchEngine liveMatchEngine;
    private final FixtureSimulator fixtureSimulator;
    private final PlayerRatingCalculator playerRatingCalculator;
    private final LiveMatchRepository liveMatchRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final CompetitionRepository competitionRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final MatchEventRepository matchEventRepository;
    private final FixtureAppearanceRepository fixtureAppearanceRepository;
    private final MatchOutcomeApplier matchOutcomeApplier;
    private final SeasonAdvanceService seasonAdvanceService;
    private final ObjectMapper objectMapper;
    private final AttendanceCalculator attendanceCalculator;
    private final WeatherService weatherService;
    private final MatchContextResolver matchContextResolver;

    public LiveMatchService(SaveGameService saveGameService, LineupService lineupService,
                            TacticsService tacticsService, FitnessCalculator fitnessCalculator,
                            LiveMatchEngine liveMatchEngine, FixtureSimulator fixtureSimulator,
                            PlayerRatingCalculator playerRatingCalculator,
                            LiveMatchRepository liveMatchRepository,
                            SeasonEventRepository seasonEventRepository, FixtureRepository fixtureRepository,
                            CompetitionRepository competitionRepository, ClubRepository clubRepository,
                            PlayerRepository playerRepository, MatchEventRepository matchEventRepository,
                            FixtureAppearanceRepository fixtureAppearanceRepository,
                            MatchOutcomeApplier matchOutcomeApplier, SeasonAdvanceService seasonAdvanceService,
                            ObjectMapper objectMapper, AttendanceCalculator attendanceCalculator,
                            WeatherService weatherService, MatchContextResolver matchContextResolver) {
        this.saveGameService = saveGameService;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.fitnessCalculator = fitnessCalculator;
        this.liveMatchEngine = liveMatchEngine;
        this.fixtureSimulator = fixtureSimulator;
        this.playerRatingCalculator = playerRatingCalculator;
        this.liveMatchRepository = liveMatchRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.competitionRepository = competitionRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.matchEventRepository = matchEventRepository;
        this.fixtureAppearanceRepository = fixtureAppearanceRepository;
        this.matchOutcomeApplier = matchOutcomeApplier;
        this.seasonAdvanceService = seasonAdvanceService;
        this.objectMapper = objectMapper;
        this.attendanceCalculator = attendanceCalculator;
        this.weatherService = weatherService;
        this.matchContextResolver = matchContextResolver;
    }

    @Transactional
    public LiveMatchState kickoff(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        save.setLastPlayedAt(Instant.now());
        Optional<LiveMatch> existing = liveMatchRepository.findBySaveGameId(saveGameId);
        if (existing.isPresent()) {
            return toState(existing.get(), save);   // resume, idempotent
        }

        SeasonEvent next = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);
        if (next == null || next.getType() != SeasonEventType.MATCH) {
            throw new ApiException(ErrorCode.NO_MATCH_TO_PLAY, "The next event is not a match");
        }
        UUID managedClubId = save.getManagedClubId();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, next.getCompetitionId(), next.getRound());
        Fixture fixture = fixtures.stream()
                .filter(f -> managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.MANAGED_CLUB_NOT_PLAYING,
                        "The managed club does not play in the next round"));

        boolean managedIsHome = managedClubId.equals(fixture.getHomeClubId());
        UUID homeClubId = fixture.getHomeClubId();
        UUID awayClubId = fixture.getAwayClubId();
        LocalDate matchDate = fixture.getMatchDate();

        List<Player> homeXi = managedIsHome ? lineupService.startingXi(saveGameId, homeClubId)
                : lineupService.bestXi(homeClubId, matchDate);
        List<Player> awayXi = managedIsHome ? lineupService.bestXi(awayClubId, matchDate)
                : lineupService.startingXi(saveGameId, awayClubId);
        List<Player> homeBench = managedIsHome ? lineupService.benchPlayers(saveGameId, homeClubId)
                : lineupService.bestBench(homeClubId, homeXi);
        List<Player> awayBench = managedIsHome ? lineupService.bestBench(awayClubId, awayXi)
                : lineupService.benchPlayers(saveGameId, awayClubId);

        long seed = saveGameId.getMostSignificantBits()
                ^ fixture.getId().getMostSignificantBits()
                ^ (long) fixture.getRound();

        LiveMatch lm = LiveMatch.create(saveGameId, fixture.getId(), next.getCompetitionId(),
                next.getRound(), matchDate, seed, managedIsHome,
                ids(homeXi), ids(awayXi), ids(homeBench), ids(awayBench), "[]");
        lm.setParallelScores(serialize(preSimulateOthers(fixtures, fixture, saveGameId, managedClubId)));
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }

    /**
     * Pre-simulates every OTHER fixture of the round (deterministically, via
     * {@link FixtureSimulator#simulatePure}) at kickoff, so {@link #finish} can replay the exact
     * same results instead of re-simulating them — avoiding any risk of divergence between what
     * the manager sees while the live match is in progress and what actually gets persisted.
     */
    private List<ParallelFixtureScore> preSimulateOthers(List<Fixture> roundFixtures, Fixture userFixture,
                                                          UUID saveGameId, UUID managedClubId) {
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, c -> c));
        return roundFixtures.stream()
                .filter(f -> !f.getId().equals(userFixture.getId()))
                .map(f -> {
                    PrecomputedFixture precomputed = fixtureSimulator.simulatePureWithXi(f, saveGameId, managedClubId);
                    SimulatedMatch match = precomputed.match();
                    return new ParallelFixtureScore(f.getId(),
                            shortName(clubs.get(f.getHomeClubId())), shortName(clubs.get(f.getAwayClubId())),
                            match.homeGoals(), match.awayGoals(), match.events(),
                            precomputed.homeXiIds(), precomputed.awayXiIds());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<LiveMatchState> getLive(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        return liveMatchRepository.findBySaveGameId(saveGameId).map(lm -> toState(lm, save));
    }

    @Transactional
    public LiveMatchState substitute(UUID saveGameId, UUID userId, SubstituteBatchRequest req) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() >= MINUTES) {
            throw new ApiException(ErrorCode.MATCH_OVER, "The match has ended");
        }

        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        List<Player> managedXi = lm.isManagedIsHome() ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = lm.isManagedIsHome() ? cfg.homeBench() : cfg.awayBench();
        OnPitch managed = onPitchManaged(lm, timeline, lm.getCommittedMinute(), managedXi);

        int used = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();
        if (used + req.subs().size() > MAX_SUBS) {
            throw new ApiException(ErrorCode.NO_SUBS_REMAINING, "No substitutions remaining");
        }

        // Validate the WHOLE batch against the evolving pitch state before persisting anything —
        // any failure rejects the batch atomically (zero subs applied).
        List<UUID> onPitch = new ArrayList<>(managed.onPitch());
        Set<UUID> usedIn = new LinkedHashSet<>(managed.usedIn());
        Set<UUID> benchIds = managedBench.stream().map(Player::getId).collect(Collectors.toSet());
        for (SubstituteRequest sub : req.subs()) {
            if (!onPitch.contains(sub.outPlayerId())) {
                throw new ApiException(ErrorCode.INVALID_SUBSTITUTION, "The outgoing player is not on the pitch");
            }
            if (!benchIds.contains(sub.inPlayerId()) || usedIn.contains(sub.inPlayerId())) {
                throw new ApiException(ErrorCode.INVALID_SUBSTITUTION,
                        "The incoming player is not an available bench player");
            }
            onPitch.remove(sub.outPlayerId());
            onPitch.add(sub.inPlayerId());
            usedIn.add(sub.inPlayerId());
        }

        int minute = Math.min(MINUTES, lm.getCommittedMinute() + 1);
        List<LiveSub> subs = new ArrayList<>(cfg.subSchedule());
        for (SubstituteRequest sub : req.subs()) {
            subs.add(new LiveSub(minute, lm.isManagedIsHome(), sub.outPlayerId(), sub.inPlayerId()));
        }
        lm.setSubSchedule(serialize(subs));
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }

    @Transactional
    public LiveMatchState changeTactics(UUID saveGameId, UUID userId, LiveTacticsRequest req) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() >= MINUTES) {
            throw new ApiException(ErrorCode.MATCH_OVER, "The match has ended");
        }

        Mentality mentality = parseMentality(req.mentality());
        GameStyle gameStyle = parseGameStyle(req.gameStyle());
        AttackZone attackZone = parseAttackZone(req.attackZone());
        Pressing pressing = parsePressing(req.pressing());
        Marking marking = parseMarking(req.marking());
        String formation = parseFormation(req.formation());

        int minute = Math.min(MINUTES, lm.getCommittedMinute() + 1);
        List<TimedTactics> tactics = new ArrayList<>(deserializeTactics(lm.getTacticsSchedule()));
        // A null gameStyle/attackZone (client omitted it) records "keep current" in the schedule entry.
        tactics.add(new TimedTactics(minute, lm.isManagedIsHome(), mentality, gameStyle, attackZone,
                pressing, marking, formation));
        lm.setTacticsSchedule(serialize(tactics));
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }

    private Mentality parseMentality(String value) {
        try { return Mentality.valueOf(value); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown mentality: " + value);
        }
    }

    /** Null/blank → null ("keep current style"); otherwise parse, rejecting an unknown style. */
    private GameStyle parseGameStyle(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try { return GameStyle.valueOf(value); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Estilo de jogo inválido: " + value);
        }
    }

    /** Null/blank → null ("keep current zone"); otherwise parse, rejecting an unknown zone. */
    private AttackZone parseAttackZone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try { return AttackZone.valueOf(value); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Zona de ataque inválida: " + value);
        }
    }

    private Pressing parsePressing(String value) {
        try { return Pressing.valueOf(value); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown pressing: " + value);
        }
    }

    private Marking parseMarking(String value) {
        try { return Marking.valueOf(value); }
        catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown marking: " + value);
        }
    }

    private String parseFormation(String code) {
        for (Formation f : Formation.values()) {
            if (f.code().equals(code)) return code;
        }
        throw new ApiException(ErrorCode.INVALID_TACTICS, "Unknown formation: " + code);
    }

    @Transactional
    public LiveMatchState advance(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() >= MINUTES) {
            return toState(lm, save);   // already at full time — no-op
        }
        StepResult step = advanceOneStep(lm);
        liveMatchRepository.save(lm);
        return toState(lm, save, step.cfg(), step.timeline());
    }

    /**
     * Skips the live match forward, server-side, to {@code HALF_TIME} or {@code FULL_TIME} —
     * looping the SAME single-step advance {@link #advanceOneStep} uses so scheduled subs/tactics
     * apply identically and the RNG is consumed in the exact same order as calling {@link #advance}
     * repeatedly. PAUSED_INJURY/PAUSED_RED never halt the loop (skipping = play on); only the
     * requested checkpoint (or full time, always) does. Idempotent: if the match is already at or
     * past the target, returns the current state unchanged without stepping.
     */
    @Transactional
    public LiveMatchState advanceTo(UUID saveGameId, UUID userId, AdvanceTarget target) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);

        int targetMinute = target == AdvanceTarget.HALF_TIME ? MINUTES / 2 : MINUTES;
        if (lm.getCommittedMinute() >= targetMinute) {
            return toState(lm, save);   // already at/past the target — idempotent no-op
        }

        StepResult step = null;
        while (lm.getCommittedMinute() < MINUTES) {
            step = advanceOneStep(lm);
            String status = statusAt(lm, step.timeline(), lm.getCommittedMinute());
            if (isAdvanceToStopStatus(target, status)) {
                break;
            }
        }
        liveMatchRepository.save(lm);
        return toState(lm, save, step.cfg(), step.timeline());
    }

    /** Single-step advance: the ONLY place committedMinute moves, shared by {@link #advance} and
     * {@link #advanceTo} so both consume the RNG in the exact same order for a given seed. */
    private StepResult advanceOneStep(LiveMatch lm) {
        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        lm.setCommittedMinute(timeline.nextEventMinute(lm.getCommittedMinute()));
        return new StepResult(cfg, timeline);
    }

    private record StepResult(LiveMatchConfig cfg, LiveTimeline timeline) {
    }

    /**
     * True when a loop skipping toward {@code target} must halt after landing on {@code status}.
     * FULL_TIME always halts (there is nothing further to skip); HALF_TIME halts only an
     * HALF_TIME-targeted skip; PAUSED_INJURY/PAUSED_RED never halt — advance-to auto-continues
     * through them.
     */
    static boolean isAdvanceToStopStatus(AdvanceTarget target, String status) {
        if ("FULL_TIME".equals(status)) {
            return true;
        }
        return target == AdvanceTarget.HALF_TIME && "HALF_TIME".equals(status);
    }

    @Transactional
    public AdvanceResponse finish(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() < MINUTES) {
            throw new ApiException(ErrorCode.MATCH_NOT_FINISHED, "The match has not reached full time");
        }

        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        Map<UUID, Player> byId = indexAll(cfg);

        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        UUID homeClubId = fixture.getHomeClubId();
        UUID awayClubId = fixture.getAwayClubId();

        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, c -> c));
        Club homeClub = clubs.get(homeClubId);
        Club awayClub = clubs.get(awayClubId);

        fixture.recordResult(timeline.homeGoals(), timeline.awayGoals());
        if (homeClub != null && awayClub != null) {
            MatchContextResolver.RoundContext roundContext =
                    matchContextResolver.forRound(save, fixture.getCompetitionId(), fixture.getMatchDate());
            fixture.setAttendance(attendanceCalculator.attendance(
                    homeClub.getStadiumCapacity(),
                    homeClub.getReputation(),
                    awayClub.getReputation(),
                    roundContext.recentFormPoints(homeClubId, fixture.getMatchDate()),
                    roundContext.leaguePosition(homeClubId),
                    roundContext.clubCount(),
                    weatherService.weatherFor(fixture, homeClub)));
        }
        fixtureRepository.save(fixture);

        // Persist real match events (goals + cards), mirroring the instant path's match_events rows.
        // A goal-family event with a non-null playerInId (open-play assist, or a corner taker who
        // IS the assist) also gets a sibling ASSIST row crediting that player.
        for (LiveTimelineEvent e : timeline.events()) {
            if (isCardOrGoal(e.type())) {
                UUID clubId = e.home() ? homeClubId : awayClubId;
                matchEventRepository.save(
                        MatchEvent.create(fixture.getId(), e.minute(), e.type().name(), e.playerId(), clubId));
                if (e.type().isGoal() && e.playerInId() != null) {
                    matchEventRepository.save(
                            MatchEvent.create(fixture.getId(), e.minute(), "ASSIST", e.playerInId(), clubId));
                }
            }
        }

        List<Player> homePlayed = playedPlayers(cfg.homeXi(), cfg.homeBench(), timeline);
        List<Player> awayPlayed = playedPlayers(cfg.awayXi(), cfg.awayBench(), timeline);

        List<AttributedEvent> attributed = timeline.events().stream()
                .filter(e -> isCardOrGoal(e.type()))
                .map(e -> new AttributedEvent(e.playerId(), e.type(), e.home()))
                .toList();

        boolean homeManaged = lm.isManagedIsHome();
        boolean awayManaged = !lm.isManagedIsHome();
        MatchOutcomeApplier.Summary summary = new MatchOutcomeApplier.Summary();
        matchOutcomeApplier.applyFixture(attributed,
                new MatchOutcomeApplier.TeamOutcome(homePlayed, timeline.homeGoals(), timeline.awayGoals(), homeManaged),
                new MatchOutcomeApplier.TeamOutcome(awayPlayed, timeline.awayGoals(), timeline.homeGoals(), awayManaged),
                summary);
        matchOutcomeApplier.applyInjuriesFromTimeline(timeline.injuries(), byId, lm.isManagedIsHome(), summary);

        List<Player> allPlayed = new ArrayList<>();
        allPlayed.addAll(homePlayed);
        allPlayed.addAll(awayPlayed);
        Map<UUID, Integer> foulsByPlayer = foulsByPlayer(timeline);
        for (Player p : allPlayed) {
            int eff = cfg.fitness().getOrDefault(p.getId(), 100);
            int minutes = timeline.minutesPlayed().getOrDefault(p.getId(), 0);
            int newBase = fitnessCalculator.decayedBaseFitness(eff, p.getStamina(), p.getAge(), p.getPosition(), minutes);
            p.recordAppearance(newBase, lm.getMatchDate());
            fixtureAppearanceRepository.save(FixtureAppearance.create(fixture.getId(), p.getId(), p.getClubId(),
                    minutes, foulsByPlayer.getOrDefault(p.getId(), 0)));
        }
        playerRepository.saveAll(allPlayed);

        FixtureResultResponse userResult = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, homeClub.getName(), shortName(homeClub), timeline.homeGoals(),
                awayClubId, awayClub.getName(), shortName(awayClub), timeline.awayGoals(),
                homeClub.resolvedStadiumName());
        Set<UUID> userPlayedIds = allPlayed.stream().map(Player::getId).collect(Collectors.toSet());

        List<PlayerRating> playerRatings = managedRatings(lm, cfg, timeline, MINUTES);
        Map<UUID, PrecomputedFixture> precomputedOthers = deserializeParallelScores(lm.getParallelScores());
        AdvanceResponse response = seasonAdvanceService.completeUserMatch(
                save, userResult, summary, fixture.getId(), userPlayedIds, playerRatings, precomputedOthers);
        liveMatchRepository.deleteBySaveGameId(saveGameId);
        return response;
    }

    /**
     * Reconstructs the round's other fixtures' {@link PrecomputedFixture}s (score + events + the
     * captured XI ids) from the JSON stored at kickoff, so {@code finish} REPLAYS them instead of
     * re-simulating. Null (in-flight matches created before {@code parallel_scores} existed)
     * yields an empty map, which routes {@code FixtureSimulator} back to its legacy
     * recompute-at-finish behaviour. Entries serialized before XI ids existed carry null
     * {@code homeXiIds}/{@code awayXiIds} — {@code FixtureSimulator} falls back to a fresh
     * {@code resolveXi} for those, exactly as before this fix.
     */
    private Map<UUID, PrecomputedFixture> deserializeParallelScores(String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            List<ParallelFixtureScore> scores =
                    objectMapper.readValue(json, new TypeReference<List<ParallelFixtureScore>>() { });
            return scores.stream().collect(Collectors.toMap(ParallelFixtureScore::fixtureId,
                    s -> new PrecomputedFixture(new SimulatedMatch(s.homeGoals(), s.awayGoals(), s.events()),
                            s.homeXiIds(), s.awayXiIds())));
        } catch (Exception e) {
            throw new RuntimeException("Failed to read parallel scores", e);
        }
    }

    private boolean isCardOrGoal(EventType t) {
        return t.isGoal() || t == EventType.YELLOW_CARD || t == EventType.RED_CARD;
    }

    private Map<UUID, Integer> foulsByPlayer(LiveTimeline timeline) {
        Map<UUID, Integer> counts = new HashMap<>();
        for (LiveTimelineEvent e : timeline.events()) {
            if (e.type() == EventType.FOUL) {
                counts.merge(e.playerId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private String shortName(Club club) {
        return club.getCommonName() != null ? club.getCommonName() : club.getName();
    }

    /** Starters (who all played) plus any bench player who actually came on (minutes > 0). */
    private List<Player> playedPlayers(List<Player> xi, List<Player> bench, LiveTimeline timeline) {
        List<Player> out = new ArrayList<>(xi);
        for (Player p : bench) {
            if (timeline.minutesPlayed().getOrDefault(p.getId(), 0) > 0) {
                out.add(p);
            }
        }
        return out;
    }

    LiveMatch requireLive(UUID saveGameId) {
        return liveMatchRepository.findBySaveGameId(saveGameId)
                .orElseThrow(() -> new ApiException(ErrorCode.NO_LIVE_MATCH, "There is no live match in progress"));
    }

    // ---- shared building blocks (reused by advance/substitute/finish) ----

    LiveMatchConfig configFor(LiveMatch lm) {
        List<Player> homeXi = resolve(deserializeIds(lm.getHomeXi()));
        List<Player> awayXi = resolve(deserializeIds(lm.getAwayXi()));
        List<Player> homeBench = resolve(deserializeIds(lm.getHomeBench()));
        List<Player> awayBench = resolve(deserializeIds(lm.getAwayBench()));
        List<LiveSub> subs = deserializeSubs(lm.getSubSchedule());

        Map<UUID, Integer> fitness = new HashMap<>();
        List<Player> everyone = new ArrayList<>();
        everyone.addAll(homeXi); everyone.addAll(awayXi); everyone.addAll(homeBench); everyone.addAll(awayBench);
        for (Player p : everyone) {
            fitness.put(p.getId(), fitnessCalculator.effectiveFitness(
                    p.getFitness(), p.getLastMatchDate(), lm.getMatchDate(), p.getAge()));
        }

        TacticalProfile homeProfile = tacticsService.profileFor(lm.getSaveGameId(),
                clubOf(homeXi), homeXi);
        TacticalProfile awayProfile = tacticsService.profileFor(lm.getSaveGameId(),
                clubOf(awayXi), awayXi);

        List<TimedTactics> tacticsSchedule = deserializeTactics(lm.getTacticsSchedule());

        Weather weather = resolveWeather(lm.getFixtureId(), clubOf(homeXi), lm.getMatchDate());

        return new LiveMatchConfig(lm.getSeed(), homeXi, awayXi, homeBench, awayBench,
                homeProfile, awayProfile, fitness, subs, tacticsSchedule, !lm.isManagedIsHome(), weather);
    }

    /**
     * Weather is always derived from the HOME club's climate zone (per the feature spec) and never
     * persisted — {@code WeatherGenerator.forFixture} is deterministic given the fixture id, so every
     * {@code configFor} call for the same live match resolves the identical weather. A missing home
     * club falls back to {@link Weather#neutral()} rather than throwing.
     */
    private Weather resolveWeather(UUID fixtureId, UUID homeClubId, LocalDate matchDate) {
        return clubRepository.findById(homeClubId)
                .map(club -> WeatherGenerator.forFixture(fixtureId, club.getClimateZone(), matchDate))
                .orElseGet(Weather::neutral);
    }

    LiveMatchState toState(LiveMatch lm, SaveGame save) {
        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        return toState(lm, save, cfg, timeline);
    }

    /**
     * Builds the state from an already-resolved config and simulated timeline. Lets the hot
     * {@link #advance} path reuse the single config/simulation it just computed instead of
     * resolving every player and re-simulating the whole match a second time.
     */
    private LiveMatchState toState(LiveMatch lm, SaveGame save, LiveMatchConfig cfg, LiveTimeline timeline) {
        int minute = lm.getCommittedMinute();

        // FOUL events exist only for fixture_appearance.fouls counting (see finish()) — silent by
        // design, they never reach the commentary feed.
        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute).stream()
                .filter(e -> e.type() != EventType.FOUL)
                .toList();
        Map<UUID, Player> byId = indexAll(cfg);
        boolean losingStreak = losingStreakHalfTime(lm.getSaveGameId(), save.getManagedClubId());
        boolean enteringMatchOnStreak = hypeStreak(lm.getSaveGameId(), save.getManagedClubId());

        // Resolved ahead of the events map below: an opponent-side goal needs the scoring club's
        // name for the sad OPP_* commentary pools (LiveCommentary.of's opponentClubName arg).
        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(lm.getSaveGameId()).stream()
                .collect(java.util.stream.Collectors.toMap(Club::getId, c -> c));
        String competitionName = competitionRepository.findById(lm.getCompetitionId())
                .map(c -> c.getName()).orElse(null);
        Club homeClub = clubs.get(fixture.getHomeClubId());
        Club awayClub = clubs.get(fixture.getAwayClubId());

        List<LiveEventResponse> events = revealed.stream().map(e -> {
            boolean opponentGoal = isOpponentGoal(e, lm.isManagedIsHome());
            String opponentClubName = opponentGoal ? (e.home() ? homeClub.getName() : awayClub.getName()) : null;
            return new LiveEventResponse(
                    e.minute(), e.type().name(), e.home(),
                    e.playerId().toString(), nameOf(byId, e.playerId()),
                    e.playerInId() == null ? null : e.playerInId().toString(),
                    e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                    e.severity(),
                    LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                            e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                            isComebackGoal(revealed, e, lm.isManagedIsHome()),
                            isHypeGoal(revealed, e, lm.isManagedIsHome(), enteringMatchOnStreak), losingStreak,
                            opponentGoal, opponentClubName));
        }).toList();

        String status = statusAt(lm, timeline, minute);
        int subsUsed = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();

        boolean managedIsHome = lm.isManagedIsHome();
        TacticalProfile managedProfile = managedIsHome ? cfg.homeProfile() : cfg.awayProfile();
        TacticalProfile opponentProfile = managedIsHome ? cfg.awayProfile() : cfg.homeProfile();

        // Managed on-pitch/bench for the sub dialog.
        List<Player> managedXi = managedIsHome ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = managedIsHome ? cfg.homeBench() : cfg.awayBench();
        OnPitch managed = onPitchManaged(lm, timeline, minute, managedXi);
        List<LivePlayerResponse> onPitch = managed.onPitch().stream()
                .map(id -> livePlayer(byId.get(id), cfg, managedProfile, true, false, managed.hobbled().contains(id))).toList();
        List<LivePlayerResponse> bench = managedBench.stream()
                .map(p -> livePlayer(p, cfg, managedProfile, false, managed.usedIn().contains(p.getId()), false)).toList();

        // Opponent on-pitch for the ratings sub-tab and the dual-formation pitch view.
        List<Player> opponentXi = managedIsHome ? cfg.awayXi() : cfg.homeXi();
        OnPitch opponent = onPitchOpponent(lm, timeline, minute, opponentXi);
        List<LivePlayerResponse> opponentOnPitch = opponent.onPitch().stream()
                .map(id -> livePlayer(byId.get(id), cfg, opponentProfile, true, false, opponent.hobbled().contains(id))).toList();

        LiveTactics currentTactics = computeCurrentTactics(lm, cfg, minute);
        LiveTactics opponentTactics = computeOpponentTactics(lm, cfg, minute);

        // Live grades: computed from what has been revealed so far (goals/cards/minutes/score through
        // the committed minute). They start near 6.0 and diverge as the game unfolds; at minute 90
        // they equal the final full-time grades. Empty at kickoff (nobody has played yet).
        List<PlayerRating> playerRatings = managedRatings(lm, cfg, timeline, minute);
        List<PlayerRating> opponentPlayerRatings = opponentRatings(lm, cfg, timeline, minute);

        KitColors homeClubColors = new KitColors(homeClub.getPrimaryColor(), homeClub.getSecondaryColor());
        KitColors awayClubColors = new KitColors(awayClub.getPrimaryColor(), awayClub.getSecondaryColor());

        return new LiveMatchState(
                lm.getFixtureId().toString(), competitionName, lm.getRound(), lm.getMatchDate().toString(),
                homeClub.resolvedStadiumName(),
                homeClub.getName(), shortName(homeClub),
                awayClub.getName(), shortName(awayClub),
                managedIsHome, minute, timeline.homeGoalsAt(minute), timeline.awayGoalsAt(minute),
                status, events, onPitch, bench, MAX_SUBS - subsUsed, currentTactics, playerRatings,
                opponentOnPitch, opponentTactics, opponentPlayerRatings, homeClubColors, awayClubColors,
                lm.getCompetitionId().toString(), parallelScoresAsOf(lm, minute));
    }

    /**
     * The round's OTHER fixtures' scores as they stand at {@code minute} (the currently-committed
     * minute) — a read-only projection over the JSON pre-simulated at kickoff, counting only goal
     * events with {@code minute <= committedMinute} so the display ticks up in step with the match
     * being played (at full time it equals each {@link ParallelFixtureScore}'s stored final score).
     * Null/blank (in-flight matches created before {@code parallel_scores} existed) yields an empty
     * list. A parse failure also yields an empty list: this is a display-only projection and must
     * never break {@code getLive}; genuine JSON corruption still surfaces loudly at {@code finish}.
     */
    private List<ParallelScore> parallelScoresAsOf(LiveMatch lm, int minute) {
        String json = lm.getParallelScores();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<ParallelFixtureScore> scores =
                    objectMapper.readValue(json, new TypeReference<List<ParallelFixtureScore>>() { });
            return scores.stream()
                    .map(pfs -> new ParallelScore(pfs.fixtureId(), pfs.homeShort(), pfs.awayShort(),
                            goalsAsOf(pfs, minute, true), goalsAsOf(pfs, minute, false)))
                    .toList();
        } catch (Exception e) {
            // Display-only projection: a corrupt snapshot must never break getLive. Log so the
            // corruption is observable — genuine JSON corruption still surfaces loudly at finish().
            log.warn("Failed to parse parallel_scores for live match {}, showing no parallel scores",
                    lm.getFixtureId(), e);
            return List.of();
        }
    }

    private int goalsAsOf(ParallelFixtureScore pfs, int minute, boolean home) {
        return (int) pfs.events().stream()
                .filter(e -> e.type().isGoal() && e.home() == home && e.minute() <= minute)
                .count();
    }

    /**
     * True only when {@code event} is a goal-family event for the side the user does not manage —
     * drives the sad OPP_* commentary pools in {@link LiveCommentary}.
     */
    static boolean isOpponentGoal(LiveTimelineEvent event, boolean managedIsHome) {
        return event.type().isGoal() && event.home() != managedIsHome;
    }

    /**
     * True only when {@code event} is a goal for the managed side that erases a deficit — the
     * managed club's goals strictly trailed the opponent's immediately before this event.
     */
    static boolean isComebackGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome) {
        if (!event.type().isGoal() || event.home() != managedIsHome) {
            return false;
        }
        int managedGoals = 0;
        int opponentGoals = 0;
        for (LiveTimelineEvent e : events) {
            if (e == event) {
                break;
            }
            if (e.type().isGoal()) {
                if (e.home() == managedIsHome) {
                    managedGoals++;
                } else {
                    opponentGoals++;
                }
            }
        }
        return managedGoals < opponentGoals;
    }

    /**
     * True only when {@code event} is the first managed-side goal in {@code events} to either
     * (a) arrive with the managed club already on a hot streak entering the match
     * ({@code enteringMatchOnStreak}), or (b) push the managed club's in-match lead to 3 or more
     * goals. Once an earlier managed goal has already qualified, later goals in the same match —
     * even ones that would also qualify — return false, so the signature line shows at most once
     * per match.
     */
    static boolean isHypeGoal(List<LiveTimelineEvent> events, LiveTimelineEvent event, boolean managedIsHome,
                               boolean enteringMatchOnStreak) {
        if (!event.type().isGoal() || event.home() != managedIsHome) {
            return false;
        }
        int managedGoals = 0;
        int opponentGoals = 0;
        for (LiveTimelineEvent e : events) {
            if (e == event) {
                break;
            }
            if (e.type().isGoal()) {
                if (e.home() == managedIsHome) {
                    managedGoals++;
                    if (enteringMatchOnStreak || managedGoals - opponentGoals >= 3) {
                        return false;
                    }
                } else {
                    opponentGoals++;
                }
            }
        }
        managedGoals++;
        return enteringMatchOnStreak || managedGoals - opponentGoals >= 3;
    }

    /** True only when {@code managedClubId}'s two most recent completed fixtures were both losses. */
    static boolean isLosingStreak(List<Fixture> mostRecentFirst, UUID managedClubId) {
        if (mostRecentFirst.size() < 2) {
            return false;
        }
        return mostRecentFirst.stream().limit(2).allMatch(f -> lostBy(f, managedClubId));
    }

    /**
     * True when {@code managedClubId} enters the match on a hot streak: its 3 most recent
     * completed fixtures were all wins, or its 5 most recent completed fixtures included no loss
     * (a draw inside the last 5 doesn't break the unbeaten check, only the 3-win check).
     */
    static boolean isHypeStreak(List<Fixture> mostRecentFirst, UUID managedClubId) {
        boolean threeWins = mostRecentFirst.size() >= 3
                && mostRecentFirst.stream().limit(3).allMatch(f -> wonBy(f, managedClubId));
        boolean fiveUnbeaten = mostRecentFirst.size() >= 5
                && mostRecentFirst.stream().limit(5).noneMatch(f -> lostBy(f, managedClubId));
        return threeWins || fiveUnbeaten;
    }

    private static boolean lostBy(Fixture f, UUID managedClubId) {
        boolean managedIsHome = f.getHomeClubId().equals(managedClubId);
        int managedGoals = managedIsHome ? f.getHomeGoals() : f.getAwayGoals();
        int opponentGoals = managedIsHome ? f.getAwayGoals() : f.getHomeGoals();
        return managedGoals < opponentGoals;
    }

    private static boolean wonBy(Fixture f, UUID managedClubId) {
        boolean managedIsHome = f.getHomeClubId().equals(managedClubId);
        int managedGoals = managedIsHome ? f.getHomeGoals() : f.getAwayGoals();
        int opponentGoals = managedIsHome ? f.getAwayGoals() : f.getHomeGoals();
        return managedGoals > opponentGoals;
    }

    private boolean losingStreakHalfTime(UUID saveGameId, UUID managedClubId) {
        return isLosingStreak(recentCompletedFixtures(saveGameId, managedClubId), managedClubId);
    }

    private boolean hypeStreak(UUID saveGameId, UUID managedClubId) {
        return isHypeStreak(recentCompletedFixtures(saveGameId, managedClubId), managedClubId);
    }

    /**
     * {@code managedClubId}'s completed fixtures involving it, most recent first. nullsFirst
     * guards against a null matchDate (unscheduled/test fixtures) — without it,
     * Comparator.comparing's natural ordering throws NPE the moment two such fixtures are compared.
     */
    private List<Fixture> recentCompletedFixtures(UUID saveGameId, UUID managedClubId) {
        return fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .sorted(java.util.Comparator.comparing(Fixture::getMatchDate,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(Fixture::getRound).reversed())
                .toList();
    }

    private List<PlayerRating> managedRatings(LiveMatch lm, LiveMatchConfig cfg, LiveTimeline timeline, int minute) {
        return ratingsFor(cfg, timeline, minute, lm.isManagedIsHome());
    }

    /**
     * Computes one side's 0–10 grades from everything revealed through {@code minute}: the
     * side's players who have featured, their goals/cards so far, minutes played so far, and
     * the score at that minute. Used live on every advance for both the managed side and the
     * opponent, and with {@code minute == MINUTES}, produces the final full-time grades
     * persisted into the news summary (managed side only).
     */
    private List<PlayerRating> opponentRatings(LiveMatch lm, LiveMatchConfig cfg, LiveTimeline timeline, int minute) {
        return ratingsFor(cfg, timeline, minute, !lm.isManagedIsHome());
    }

    private List<PlayerRating> ratingsFor(LiveMatchConfig cfg, LiveTimeline timeline, int minute, boolean isHome) {
        List<Player> xi = isHome ? cfg.homeXi() : cfg.awayXi();
        List<Player> bench = isHome ? cfg.homeBench() : cfg.awayBench();

        Map<UUID, Integer> minutes = minutesThrough(xi, timeline, minute, isHome);
        List<Player> everyone = new ArrayList<>(xi);
        everyone.addAll(bench);   // rate() self-filters to players with minutes > 0

        // A missed penalty must reach the rating calculator (it is NOT persisted; the finish()
        // persist loop and match-event repository still key off plain isCardOrGoal).
        List<AttributedEvent> sideEvents = timeline.eventsThrough(minute).stream()
                .filter(e -> e.home() == isHome
                        && (isCardOrGoal(e.type()) || e.type() == EventType.PENALTY_MISSED))
                .map(e -> new AttributedEvent(e.playerId(), e.type(), e.home()))
                .toList();

        int scored = isHome ? timeline.homeGoalsAt(minute) : timeline.awayGoalsAt(minute);
        int conceded = isHome ? timeline.awayGoalsAt(minute) : timeline.homeGoalsAt(minute);
        return playerRatingCalculator.rate(everyone, sideEvents, minutes, scored, conceded);
    }

    /**
     * Minutes each managed player has spent on the pitch through {@code minute}, derived from the
     * revealed events: starters enter at kickoff, a substitution moves the exit/entry minute, a red
     * card ends a player's game. Players still on the pitch are credited up to {@code minute}. Only
     * players with more than zero minutes are returned.
     */
    private Map<UUID, Integer> minutesThrough(List<Player> managedXi, LiveTimeline timeline,
                                              int minute, boolean managedIsHome) {
        Map<UUID, Integer> entry = new LinkedHashMap<>();
        Map<UUID, Integer> exit = new HashMap<>();
        for (Player p : managedXi) {
            entry.put(p.getId(), 0);
        }
        for (LiveTimelineEvent e : timeline.eventsThrough(minute)) {
            if (e.home() != managedIsHome) continue;
            switch (e.type()) {
                case SUBSTITUTION -> {
                    exit.put(e.playerId(), e.minute());
                    entry.put(e.playerInId(), e.minute());
                }
                case RED_CARD -> exit.put(e.playerId(), e.minute());
                default -> { }
            }
        }
        Map<UUID, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<UUID, Integer> en : entry.entrySet()) {
            int played = Math.max(0, exit.getOrDefault(en.getKey(), minute) - en.getValue());
            if (played > 0) {
                out.put(en.getKey(), played);
            }
        }
        return out;
    }

    /** Derives the managed side's active tactics at {@code minute}, applying any scheduled changes. */
    private LiveTactics computeCurrentTactics(LiveMatch lm, LiveMatchConfig cfg, int minute) {
        return tacticsFor(cfg, minute, lm.isManagedIsHome());
    }

    /** Derives the opponent side's active tactics at {@code minute}, applying any scheduled changes. */
    private LiveTactics computeOpponentTactics(LiveMatch lm, LiveMatchConfig cfg, int minute) {
        return tacticsFor(cfg, minute, !lm.isManagedIsHome());
    }

    private LiveTactics tacticsFor(LiveMatchConfig cfg, int minute, boolean isHome) {
        TacticalProfile base = isHome ? cfg.homeProfile() : cfg.awayProfile();
        String mentality = base.mentality().name();
        String pressing = base.pressing().name();
        String marking = base.marking().name();
        String formation = base.formation();
        String gameStyle = base.gameStyle().name();
        String attackZone = base.attackZone().name();

        // Apply the last scheduled change for this side whose minute <= committedMinute
        for (TimedTactics tt : cfg.tacticsSchedule()) {
            if (tt.home() == isHome && tt.minute() <= minute) {
                mentality = tt.mentality().name();
                pressing = tt.pressing().name();
                marking = tt.marking().name();
                formation = tt.formation();
                // A null gameStyle entry (client omitted it, or a legacy schedule) keeps the current style.
                if (tt.gameStyle() != null) {
                    gameStyle = tt.gameStyle().name();
                }
                // A null attackZone entry (client omitted it, or a legacy schedule) keeps the current zone.
                if (tt.attackZone() != null) {
                    attackZone = tt.attackZone().name();
                }
            }
        }
        return new LiveTactics(mentality, pressing, marking, formation, gameStyle, attackZone);
    }

    /** One side's current on-pitch set, hobbled set, and bench-players-already-used, at {@code minute}. */
    private record OnPitch(List<UUID> onPitch, Set<UUID> hobbled, Set<UUID> usedIn) {
    }

    private OnPitch onPitchManaged(LiveMatch lm, LiveTimeline timeline, int minute, List<Player> managedXi) {
        return onPitchFor(timeline, minute, lm.isManagedIsHome(), managedXi);
    }

    private OnPitch onPitchOpponent(LiveMatch lm, LiveTimeline timeline, int minute, List<Player> opponentXi) {
        return onPitchFor(timeline, minute, !lm.isManagedIsHome(), opponentXi);
    }

    private OnPitch onPitchFor(LiveTimeline timeline, int minute, boolean isHome, List<Player> xi) {
        List<UUID> onPitch = new ArrayList<>(xi.stream().map(Player::getId).toList());
        Set<UUID> hobbled = new LinkedHashSet<>();
        Set<UUID> usedIn = new LinkedHashSet<>();
        for (LiveTimelineEvent e : timeline.eventsThrough(minute)) {
            if (e.home() != isHome) continue;
            switch (e.type()) {
                case SUBSTITUTION -> {
                    onPitch.remove(e.playerId());
                    onPitch.add(e.playerInId());
                    usedIn.add(e.playerInId());
                }
                case RED_CARD -> onPitch.remove(e.playerId());
                case INJURY -> hobbled.add(e.playerId());
                default -> { }
            }
        }
        return new OnPitch(onPitch, hobbled, usedIn);
    }

    private String statusAt(LiveMatch lm, LiveTimeline timeline, int minute) {
        if (minute >= MINUTES) return "FULL_TIME";
        // A forced pause happens when the LAST revealed event at the committed minute is a special marker.
        // HALF_TIME is appended last at minute 45 and therefore wins when present.
        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
        for (int i = revealed.size() - 1; i >= 0; i--) {
            LiveTimelineEvent e = revealed.get(i);
            if (e.minute() != minute) break;
            if (e.type() == EventType.HALF_TIME) return "HALF_TIME";
            if (e.home() == lm.isManagedIsHome() && e.type() == EventType.INJURY) return "PAUSED_INJURY";
            if (e.home() == lm.isManagedIsHome() && e.type() == EventType.RED_CARD) return "PAUSED_RED";
        }
        return "IN_PROGRESS";
    }

    private LivePlayerResponse livePlayer(Player p, LiveMatchConfig cfg, TacticalProfile profile,
                                          boolean onPitch, boolean used, boolean hobbled) {
        int fit = cfg.fitness().getOrDefault(p.getId(), 100);
        RoleAssignment role = RoleAssignment.resolve(profile.roles(), p);
        return new LivePlayerResponse(p.getId().toString(), p.getName(), p.getPosition().name(),
                p.getOverall(), fit, hobbled, onPitch, used, p.isEligible(), role.archetype().name());
    }

    // ---- helpers ----

    private String ids(List<Player> players) {
        return serialize(players.stream().map(p -> p.getId().toString()).toList());
    }

    /** Fetches every player in one query (not N+1) and returns them in the requested id order. */
    private List<Player> resolve(List<UUID> ids) {
        Map<UUID, Player> byId = playerRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Player::getId, p -> p));
        return ids.stream().map(id -> {
            Player p = byId.get(id);
            if (p == null) {
                throw new ApiException(ErrorCode.PLAYER_NOT_FOUND, "Player " + id + " not found");
            }
            return p;
        }).toList();
    }

    private UUID clubOf(List<Player> xi) {
        return xi.get(0).getClubId();
    }

    private Map<UUID, Player> indexAll(LiveMatchConfig cfg) {
        Map<UUID, Player> m = new HashMap<>();
        cfg.homeXi().forEach(p -> m.put(p.getId(), p));
        cfg.awayXi().forEach(p -> m.put(p.getId(), p));
        cfg.homeBench().forEach(p -> m.put(p.getId(), p));
        cfg.awayBench().forEach(p -> m.put(p.getId(), p));
        return m;
    }

    private String nameOf(Map<UUID, Player> byId, UUID id) {
        Player p = byId.get(id);
        return p == null ? "" : p.getName();
    }

    String serialize(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception e) { throw new RuntimeException("Failed to serialize live-match data", e); }
    }

    private List<UUID> deserializeIds(String json) {
        try {
            List<String> raw = objectMapper.readValue(json, new TypeReference<List<String>>() { });
            return raw.stream().map(UUID::fromString).toList();
        } catch (Exception e) { throw new RuntimeException("Failed to read live-match ids", e); }
    }

    List<LiveSub> deserializeSubs(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<LiveSub>>() { }); }
        catch (Exception e) { throw new RuntimeException("Failed to read sub schedule", e); }
    }

    List<TimedTactics> deserializeTactics(String json) {
        try { return objectMapper.readValue(json, new TypeReference<List<TimedTactics>>() { }); }
        catch (Exception e) { throw new RuntimeException("Failed to read tactics schedule", e); }
    }
}
