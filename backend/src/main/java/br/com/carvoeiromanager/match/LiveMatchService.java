package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.common.ApiException;
import br.com.carvoeiromanager.competition.CompetitionRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import br.com.carvoeiromanager.season.SeasonEventType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class LiveMatchService {

    static final int MAX_SUBS = 5;
    static final int MINUTES = 90;

    private final SaveGameService saveGameService;
    private final LineupService lineupService;
    private final TacticsService tacticsService;
    private final FitnessCalculator fitnessCalculator;
    private final LiveMatchEngine liveMatchEngine;
    private final LiveMatchRepository liveMatchRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final CompetitionRepository competitionRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final ObjectMapper objectMapper;

    public LiveMatchService(SaveGameService saveGameService, LineupService lineupService,
                            TacticsService tacticsService, FitnessCalculator fitnessCalculator,
                            LiveMatchEngine liveMatchEngine, LiveMatchRepository liveMatchRepository,
                            SeasonEventRepository seasonEventRepository, FixtureRepository fixtureRepository,
                            CompetitionRepository competitionRepository, ClubRepository clubRepository,
                            PlayerRepository playerRepository, ObjectMapper objectMapper) {
        this.saveGameService = saveGameService;
        this.lineupService = lineupService;
        this.tacticsService = tacticsService;
        this.fitnessCalculator = fitnessCalculator;
        this.liveMatchEngine = liveMatchEngine;
        this.liveMatchRepository = liveMatchRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.competitionRepository = competitionRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public LiveMatchState kickoff(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        Optional<LiveMatch> existing = liveMatchRepository.findBySaveGameId(saveGameId);
        if (existing.isPresent()) {
            return toState(existing.get(), save);   // resume, idempotent
        }

        SeasonEvent next = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(saveGameId);
        if (next == null || next.getType() != SeasonEventType.MATCH) {
            throw new ApiException("NO_MATCH_TO_PLAY", "The next event is not a match", HttpStatus.CONFLICT);
        }
        UUID managedClubId = save.getManagedClubId();
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndCompetitionIdAndRound(
                saveGameId, next.getCompetitionId(), next.getRound());
        Fixture fixture = fixtures.stream()
                .filter(f -> managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()))
                .findFirst()
                .orElseThrow(() -> new ApiException("MANAGED_CLUB_NOT_PLAYING",
                        "The managed club does not play in the next round", HttpStatus.CONFLICT));

        boolean managedIsHome = managedClubId.equals(fixture.getHomeClubId());
        UUID homeClubId = fixture.getHomeClubId();
        UUID awayClubId = fixture.getAwayClubId();
        LocalDate matchDate = fixture.getMatchDate();

        List<Player> homeXi = managedIsHome ? lineupService.startingXi(saveGameId, homeClubId)
                : lineupService.bestXi(homeClubId, matchDate);
        List<Player> awayXi = managedIsHome ? lineupService.bestXi(awayClubId, matchDate)
                : lineupService.startingXi(saveGameId, awayClubId);
        List<Player> homeBench = managedIsHome ? lineupService.benchPlayers(saveGameId, homeClubId) : List.of();
        List<Player> awayBench = managedIsHome ? List.of() : lineupService.benchPlayers(saveGameId, awayClubId);

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
    public LiveMatchState advance(UUID saveGameId, UUID userId) {
        SaveGame save = saveGameService.requireOwned(saveGameId, userId);
        LiveMatch lm = requireLive(saveGameId);
        if (lm.getCommittedMinute() >= MINUTES) {
            return toState(lm, save);   // already at full time — no-op
        }
        LiveTimeline timeline = liveMatchEngine.simulate(configFor(lm));
        lm.setCommittedMinute(timeline.nextEventMinute(lm.getCommittedMinute()));
        liveMatchRepository.save(lm);
        return toState(lm, save);
    }

    LiveMatch requireLive(UUID saveGameId) {
        return liveMatchRepository.findBySaveGameId(saveGameId)
                .orElseThrow(() -> new ApiException("NO_LIVE_MATCH",
                        "There is no live match in progress", HttpStatus.CONFLICT));
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

        return new LiveMatchConfig(lm.getSeed(), homeXi, awayXi, homeBench, awayBench,
                homeProfile, awayProfile, fitness, subs);
    }

    LiveMatchState toState(LiveMatch lm, SaveGame save) {
        LiveMatchConfig cfg = configFor(lm);
        LiveTimeline timeline = liveMatchEngine.simulate(cfg);
        int minute = lm.getCommittedMinute();

        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
        Map<UUID, Player> byId = indexAll(cfg);
        List<LiveEventResponse> events = revealed.stream().map(e -> new LiveEventResponse(
                e.minute(), e.type().name(), e.home(),
                e.playerId().toString(), nameOf(byId, e.playerId()),
                e.playerInId() == null ? null : e.playerInId().toString(),
                e.playerInId() == null ? null : nameOf(byId, e.playerInId()),
                e.severity(),
                LiveCommentary.of(e.type(), nameOf(byId, e.playerId()),
                        e.playerInId() == null ? null : nameOf(byId, e.playerInId()), e.minute()))).toList();

        String status = statusAt(lm, timeline, minute);
        int subsUsed = (int) cfg.subSchedule().stream().filter(s -> s.home() == lm.isManagedIsHome()).count();

        Fixture fixture = fixtureRepository.findById(lm.getFixtureId()).orElseThrow();
        Map<UUID, Club> clubs = clubRepository.findBySaveGameId(lm.getSaveGameId()).stream()
                .collect(java.util.stream.Collectors.toMap(Club::getId, c -> c));
        String competitionName = competitionRepository.findById(lm.getCompetitionId())
                .map(c -> c.getName()).orElse(null);

        // Managed on-pitch/bench for the sub dialog.
        List<Player> managedXi = lm.isManagedIsHome() ? cfg.homeXi() : cfg.awayXi();
        List<Player> managedBench = lm.isManagedIsHome() ? cfg.homeBench() : cfg.awayBench();
        OnPitch managed = onPitchManaged(lm, timeline, minute, managedXi);
        List<LivePlayerResponse> onPitch = managed.onPitch().stream()
                .map(id -> livePlayer(byId.get(id), cfg, true, false, managed.hobbled().contains(id))).toList();
        List<LivePlayerResponse> bench = managedBench.stream()
                .map(p -> livePlayer(p, cfg, false, managed.usedIn().contains(p.getId()), false)).toList();

        return new LiveMatchState(
                lm.getFixtureId().toString(), competitionName, lm.getRound(), lm.getMatchDate().toString(),
                clubs.get(fixture.getHomeClubId()).getName(), clubs.get(fixture.getAwayClubId()).getName(),
                lm.isManagedIsHome(), minute, timeline.homeGoalsAt(minute), timeline.awayGoalsAt(minute),
                status, events, onPitch, bench, MAX_SUBS - subsUsed);
    }

    /** Managed side's current on-pitch set, hobbled set, and bench-players-already-used, at {@code minute}. */
    private record OnPitch(List<UUID> onPitch, Set<UUID> hobbled, Set<UUID> usedIn) {
    }

    private OnPitch onPitchManaged(LiveMatch lm, LiveTimeline timeline, int minute, List<Player> managedXi) {
        List<UUID> onPitch = new ArrayList<>(managedXi.stream().map(Player::getId).toList());
        Set<UUID> hobbled = new LinkedHashSet<>();
        Set<UUID> usedIn = new LinkedHashSet<>();
        for (LiveTimelineEvent e : timeline.eventsThrough(minute)) {
            if (e.home() != lm.isManagedIsHome()) continue;
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
        // A forced pause happens when the LAST revealed event is a managed-side injury or red card.
        List<LiveTimelineEvent> revealed = timeline.eventsThrough(minute);
        for (int i = revealed.size() - 1; i >= 0; i--) {
            LiveTimelineEvent e = revealed.get(i);
            if (e.minute() != minute) break;
            if (e.home() == lm.isManagedIsHome() && e.type() == EventType.INJURY) return "PAUSED_INJURY";
            if (e.home() == lm.isManagedIsHome() && e.type() == EventType.RED_CARD) return "PAUSED_RED";
        }
        return "IN_PROGRESS";
    }

    private LivePlayerResponse livePlayer(Player p, LiveMatchConfig cfg, boolean onPitch, boolean used, boolean hobbled) {
        int fit = cfg.fitness().getOrDefault(p.getId(), 100);
        return new LivePlayerResponse(p.getId().toString(), p.getName(), p.getPosition().name(),
                p.getOverall(), fit, hobbled, onPitch, used, p.isEligible());
    }

    // ---- helpers ----

    private String ids(List<Player> players) {
        return serialize(players.stream().map(p -> p.getId().toString()).toList());
    }

    private List<Player> resolve(List<UUID> ids) {
        return ids.stream().map(id -> playerRepository.findById(id).orElseThrow(() ->
                new ApiException("PLAYER_NOT_FOUND", "Player " + id + " not found", HttpStatus.NOT_FOUND))).toList();
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
}
