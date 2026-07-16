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
import br.com.carvoeiromanager.player.Formation;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Pressing;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
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

    static final int MAX_SUBS = 5;
    static final int MINUTES = 90;

    private final SaveGameService saveGameService;
    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final FitnessCalculator fitnessCalculator;
    private final LiveMatchEngine liveMatchEngine;
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

    public LiveMatchService(SaveGameService saveGameService, LineupService lineupService,
                            TacticsService tacticsService, FitnessCalculator fitnessCalculator,
                            LiveMatchEngine liveMatchEngine, PlayerRatingCalculator playerRatingCalculator,
                            LiveMatchRepository liveMatchRepository,
                            SeasonEventRepository seasonEventRepository, FixtureRepository fixtureRepository,
                            CompetitionRepository competitionRepository, ClubRepository clubRepository,
                            PlayerRepository playerRepository, MatchEventRepository matchEventRepository,
                            FixtureAppearanceRepository fixtureAppearanceRepository,
                            MatchOutcomeApplier matchOutcomeApplier, SeasonAdvanceService seasonAdvanceService,
                            ObjectMapper objectMapper) {
        this.saveGameService = saveGameService;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.fitnessCalculator = fitnessCalculator;
        this.liveMatchEngine = liveMatchEngine;
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
        liveMatchRepository.save(lm);
        return toState(lm, save);
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
        Pressing pressing = parsePressing(req.pressing());
        Marking marking = parseMarking(req.marking());
        String formation = parseFormation(req.formation());

        int minute = Math.min(MINUTES, lm.getCommittedMinute() + 1);
        List<TimedTactics> tactics = new ArrayList<>(deserializeTactics(lm.getTacticsSchedule()));
        tactics.add(new TimedTactics(minute, lm.isManagedIsHome(), mentality, pressing, marking, formation));
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
        // Resolve the lineup and simulate once, then reuse both for building the state below —
        // this is the per-event hot path, so avoid a second configFor()+simulate() in toState().
        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        lm.setCommittedMinute(timeline.nextEventMinute(lm.getCommittedMinute()));
        liveMatchRepository.save(lm);
        return toState(lm, save, cfg, timeline);
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
        fixture.recordResult(timeline.homeGoals(), timeline.awayGoals());
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

        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(saveGameId).stream()
                .collect(Collectors.toMap(Club::getId, c -> c));
        FixtureResultResponse userResult = new FixtureResultResponse(
                fixture.getId(),
                homeClubId, clubs.get(homeClubId).getName(), shortName(clubs.get(homeClubId)), timeline.homeGoals(),
                awayClubId, clubs.get(awayClubId).getName(), shortName(clubs.get(awayClubId)), timeline.awayGoals());
        Set<UUID> userPlayedIds = allPlayed.stream().map(Player::getId).collect(Collectors.toSet());

        List<PlayerRating> playerRatings = managedRatings(lm, cfg, timeline, MINUTES);
        AdvanceResponse response = seasonAdvanceService.completeUserMatch(
                save, userResult, summary, fixture.getId(), userPlayedIds, playerRatings);
        liveMatchRepository.deleteBySaveGameId(saveGameId);
        return response;
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

        return new LiveMatchConfig(lm.getSeed(), homeXi, awayXi, homeBench, awayBench,
                homeProfile, awayProfile, fitness, subs, tacticsSchedule, !lm.isManagedIsHome());
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
        List<LiveEventResponse> events = revealed.stream().map(e -> new LiveEventResponse(
                e.minute(), e.type().name(), e.home(),
                e.playerId().toString(), nameOf(byId, e.playerId()),
                e.playerInId() == null ? null : e.playerInId().toString(),
                e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                e.severity(),
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute(), e.detail(),
                        isComebackGoal(revealed, e, lm.isManagedIsHome()), losingStreak))).toList();

        String status = statusAt(lm, timeline, minute);
        int subsUsed = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();

        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(lm.getSaveGameId()).stream()
                .collect(java.util.stream.Collectors.toMap(Club::getId, c -> c));
        String competitionName = competitionRepository.findById(lm.getCompetitionId())
                .map(c -> c.getName()).orElse(null);

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

        Club homeClub = clubs.get(fixture.getHomeClubId());
        Club awayClub = clubs.get(fixture.getAwayClubId());
        KitColors homeClubColors = new KitColors(homeClub.getPrimaryColor(), homeClub.getSecondaryColor());
        KitColors awayClubColors = new KitColors(awayClub.getPrimaryColor(), awayClub.getSecondaryColor());

        return new LiveMatchState(
                lm.getFixtureId().toString(), competitionName, lm.getRound(), lm.getMatchDate().toString(),
                homeClub.getName(), shortName(homeClub),
                awayClub.getName(), shortName(awayClub),
                managedIsHome, minute, timeline.homeGoalsAt(minute), timeline.awayGoalsAt(minute),
                status, events, onPitch, bench, MAX_SUBS - subsUsed, currentTactics, playerRatings,
                opponentOnPitch, opponentTactics, opponentPlayerRatings, homeClubColors, awayClubColors);
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

    /** True when {@code managedClubId}'s two most recent completed fixtures were both losses. */
    static boolean isLosingStreak(List<Fixture> mostRecentFirst, UUID managedClubId) {
        if (mostRecentFirst.size() < 2) {
            return false;
        }
        return mostRecentFirst.stream().limit(2).allMatch(f -> lostBy(f, managedClubId));
    }

    private static boolean lostBy(Fixture f, UUID managedClubId) {
        boolean managedIsHome = f.getHomeClubId().equals(managedClubId);
        int managedGoals = managedIsHome ? f.getHomeGoals() : f.getAwayGoals();
        int opponentGoals = managedIsHome ? f.getAwayGoals() : f.getHomeGoals();
        return managedGoals < opponentGoals;
    }

    private boolean losingStreakHalfTime(UUID saveGameId, UUID managedClubId) {
        // nullsFirst guards against a null matchDate (unscheduled/test fixtures) — without it,
        // Comparator.comparing's natural ordering throws NPE the moment two such fixtures are compared.
        List<Fixture> recent = fixtureRepository.findBySaveGameId(saveGameId).stream()
                .filter(Fixture::isPlayed)
                .filter(f -> f.getHomeClubId().equals(managedClubId) || f.getAwayClubId().equals(managedClubId))
                .sorted(java.util.Comparator.comparing(Fixture::getMatchDate,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder()))
                        .thenComparing(Fixture::getRound).reversed())
                .toList();
        return isLosingStreak(recent, managedClubId);
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

        // Apply the last scheduled change for this side whose minute <= committedMinute
        for (TimedTactics tt : cfg.tacticsSchedule()) {
            if (tt.home() == isHome && tt.minute() <= minute) {
                mentality = tt.mentality().name();
                pressing = tt.pressing().name();
                marking = tt.marking().name();
                formation = tt.formation();
            }
        }
        return new LiveTactics(mentality, pressing, marking, formation);
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
        PlayerRole role = profile.roles().getOrDefault(p.getId(), PlayerRole.defaultFor(p.getPosition()));
        return new LivePlayerResponse(p.getId().toString(), p.getName(), p.getPosition().name(),
                p.getOverall(), fit, hobbled, onPitch, used, p.isEligible(), role.name());
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
