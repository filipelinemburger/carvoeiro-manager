package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.competition.Fixture;
import br.com.carvoeiromanager.competition.FixtureRepository;
import br.com.carvoeiromanager.competition.StandingRow;
import br.com.carvoeiromanager.competition.StandingsCalculator;
import br.com.carvoeiromanager.loan.LoanEligibility;
import br.com.carvoeiromanager.match.Weather;
import br.com.carvoeiromanager.match.WeatherService;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.stats.PlayerStatsService;
import br.com.carvoeiromanager.stats.PlayerStatsService.CompetitionPlayerAggregate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Persists the Auxiliar Técnico's pre-match opponent analysis as a {@code MATCH_PREVIEW}
 * news item dated the eve of the managed club's next fixture. Self-guarding — callers
 * invoke it unconditionally after every advance, at rollover, and on assistant hire:
 * <ul>
 *   <li>no upcoming managed-club MATCH event → no-op;</li>
 *   <li>no ASSISTENTE hired → no-op (the hiring incentive);</li>
 *   <li>a preview for this fixture already exists → no-op (previews are written in fixture
 *       order, so checking the latest one is sufficient).</li>
 * </ul>
 * Analysis depth is gated server-side by the assistant's two attributes (see
 * {@link MatchPreviewData}); the basics (standings + form) are always included.
 */
@Service
public class MatchPreviewService {

    static final String NEWS_TYPE = "MATCH_PREVIEW";
    private static final int TIER_FULL = 16;
    private static final int TIER_MID = 12;

    private final SaveGameRepository saveGameRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final FixtureRepository fixtureRepository;
    private final ClubRepository clubRepository;
    private final PlayerRepository playerRepository;
    private final StaffRepository staffRepository;
    private final PlayerStatsService playerStatsService;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;
    private final WeatherService weatherService;
    private final LoanEligibility loanEligibility;
    private final StandingsCalculator standingsCalculator = new StandingsCalculator();

    public MatchPreviewService(SaveGameRepository saveGameRepository,
                               SeasonEventRepository seasonEventRepository,
                               FixtureRepository fixtureRepository,
                               ClubRepository clubRepository,
                               PlayerRepository playerRepository,
                               StaffRepository staffRepository,
                               PlayerStatsService playerStatsService,
                               NewsItemRepository newsItemRepository,
                               ObjectMapper objectMapper,
                               WeatherService weatherService,
                               LoanEligibility loanEligibility) {
        this.saveGameRepository = saveGameRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.fixtureRepository = fixtureRepository;
        this.clubRepository = clubRepository;
        this.playerRepository = playerRepository;
        this.staffRepository = staffRepository;
        this.playerStatsService = playerStatsService;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
        this.weatherService = weatherService;
        this.loanEligibility = loanEligibility;
    }

    @Transactional
    public void maybePersistPreview(UUID saveGameId) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElse(null);
        if (save == null) {
            return;
        }
        UUID managedClubId = save.getManagedClubId();

        // Scoped to the managed club — an opponent analysis for a background fixture the player
        // never plays would be noise in their feed.
        SeasonEvent nextMatch = seasonEventRepository
                .findNextManagedMatchEvent(saveGameId, managedClubId);
        if (nextMatch == null) {
            return;
        }
        Fixture fixture = fixtureRepository
                .findBySaveGameIdAndCompetitionIdAndRound(saveGameId, nextMatch.getCompetitionId(), nextMatch.getRound())
                .stream()
                .filter(f -> managedClubId.equals(f.getHomeClubId()) || managedClubId.equals(f.getAwayClubId()))
                .findFirst()
                .orElse(null);
        if (fixture == null) {
            return;
        }
        Staff assistant = staffRepository.findByClubIdAndRole(managedClubId, StaffRole.ASSISTENTE)
                .orElse(null);
        if (assistant == null) {
            return;
        }
        if (alreadyPreviewed(saveGameId, fixture.getId())) {
            return;
        }

        MatchPreviewData preview = buildPreview(save, nextMatch, fixture, assistant);
        persist(saveGameId, fixture.getMatchDate().minusDays(1), preview);
    }

    private boolean alreadyPreviewed(UUID saveGameId, UUID fixtureId) {
        NewsItem last = newsItemRepository.findFirstBySaveGameIdAndTypeOrderBySeqDesc(saveGameId, NEWS_TYPE);
        if (last == null) {
            return false;
        }
        try {
            AdvanceResponse payload = objectMapper.readValue(last.getPayload(), AdvanceResponse.class);
            return payload.matchPreview() != null
                    && Objects.equals(payload.matchPreview().fixtureId(), fixtureId.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private MatchPreviewData buildPreview(SaveGame save, SeasonEvent event, Fixture fixture, Staff assistant) {
        UUID managedClubId = save.getManagedClubId();
        boolean home = managedClubId.equals(fixture.getHomeClubId());
        UUID opponentId = home ? fixture.getAwayClubId() : fixture.getHomeClubId();
        Club opponent = clubRepository.findById(opponentId).orElseThrow();
        Club venueClub = clubRepository.findById(fixture.getHomeClubId()).orElseThrow();

        List<Fixture> competitionFixtures = fixtureRepository
                .findBySaveGameIdAndCompetitionId(save.getId(), event.getCompetitionId());
        List<Fixture> played = competitionFixtures.stream().filter(Fixture::isPlayed).toList();
        List<UUID> clubIds = competitionFixtures.stream()
                .flatMap(f -> Stream.of(f.getHomeClubId(), f.getAwayClubId()))
                .distinct()
                .toList();
        List<Club> clubs = clubRepository.findAllById(clubIds);
        List<StandingRow> table = standingsCalculator.compute(clubs, played);

        int conhecimentoTatico = assistant.getPrimaryAttribute();
        int avaliacaoDeJogadores = assistant.getSecondaryAttribute();

        // The panel previews a specific fixture — a lent player barred from facing his owner (see
        // LoanEligibility) must not headline as the opponent's danger man for a match he cannot play.
        List<Player> opponentPlayers = playerRepository.findByClubIdAndYouthProspectFalse(opponentId).stream()
                .filter(p -> loanEligibility.mayFace(p, managedClubId))
                .sorted(Comparator.comparingInt(Player::getOverall).reversed()
                        .thenComparing(Player::getName))
                .toList();
        int playerCount = avaliacaoDeJogadores >= TIER_MID ? 3 : 1;
        List<MatchPreviewData.PreviewPlayer> bestPlayers = opponentPlayers.stream()
                .limit(playerCount)
                .map(p -> new MatchPreviewData.PreviewPlayer(p.getName(), p.getPosition().name(), p.getOverall()))
                .toList();

        MatchPreviewData.PreviewTopScorer topScorer = avaliacaoDeJogadores >= TIER_FULL
                ? topScorerFor(save.getId(), event.getCompetitionId(), opponentId)
                : null;

        MatchPreviewData.TacticalRead tacticalRead = conhecimentoTatico >= TIER_MID
                ? new MatchPreviewData.TacticalRead(opponent.getDefaultFormation(),
                        opponent.getDefaultMentality(), opponent.getDefaultPressing(), opponent.getDefaultMarking())
                : null;

        List<String> insights = null;
        if (conhecimentoTatico >= TIER_FULL) {
            int opponentAvg = averageOverall(opponentPlayers);
            int managedAvg = averageOverall(playerRepository.findByClubIdAndYouthProspectFalse(managedClubId));
            long seed = fixture.getId().getMostSignificantBits() ^ fixture.getId().getLeastSignificantBits();
            insights = MatchPreviewInsights.compose(seed, opponent.getDefaultMentality(),
                    opponent.getDefaultPressing(), opponent.getDefaultMarking(),
                    opponentAvg - managedAvg, home);
        }

        // The preview is dated the eve of the fixture, so resolving through visibleWeather with
        // that same date will always be revealed — routed through the service anyway so there is
        // exactly one reveal rule in the codebase.
        LocalDate previewDate = fixture.getMatchDate().minusDays(1);
        Optional<Weather> weather = weatherService.visibleWeather(fixture, venueClub, previewDate);
        String weatherLabel = weather.map(w -> w.condition().name()).orElse(null);
        Integer temperatureC = weather.map(Weather::temperatureC).orElse(null);
        String weatherAdvice = weather.map(MatchPreviewService::weatherAdvice).orElse(null);

        return new MatchPreviewData(
                fixture.getId().toString(),
                event.getRound(),
                fixture.getMatchDate().toString(),
                home,
                venueClub.resolvedStadiumName(),
                weatherLabel,
                temperatureC,
                weatherAdvice,
                opponentId.toString(),
                opponent.getCommonName() != null ? opponent.getCommonName() : opponent.getName(),
                opponent.getShortName(),
                assistant.getName(),
                snapshotFor(table, opponentId),
                snapshotFor(table, managedClubId),
                formFor(played, opponentId),
                bestPlayers,
                topScorer,
                tacticalRead,
                insights);
    }

    /** 1-based position + counters for one club; null only if the club is not in the table. */
    private MatchPreviewData.StandingSnapshot snapshotFor(List<StandingRow> table, UUID clubId) {
        for (int i = 0; i < table.size(); i++) {
            StandingRow row = table.get(i);
            if (row.clubId().equals(clubId)) {
                return new MatchPreviewData.StandingSnapshot(i + 1, row.points(), row.played(),
                        row.won(), row.drawn(), row.lost(), row.goalsFor(), row.goalsAgainst());
            }
        }
        return null;
    }

    /** The opponent's last up-to-5 played results, newest first, "V"/"E"/"D" from their POV. */
    private List<String> formFor(List<Fixture> played, UUID clubId) {
        return played.stream()
                .filter(f -> clubId.equals(f.getHomeClubId()) || clubId.equals(f.getAwayClubId()))
                .sorted(Comparator.comparingInt(Fixture::getRound).reversed())
                .limit(5)
                .map(f -> {
                    boolean wasHome = clubId.equals(f.getHomeClubId());
                    int scored = wasHome ? f.getHomeGoals() : f.getAwayGoals();
                    int conceded = wasHome ? f.getAwayGoals() : f.getHomeGoals();
                    return scored > conceded ? "V" : scored < conceded ? "D" : "E";
                })
                .toList();
    }

    private MatchPreviewData.PreviewTopScorer topScorerFor(UUID saveGameId, UUID competitionId, UUID clubId) {
        Map<UUID, CompetitionPlayerAggregate> aggregates =
                playerStatsService.aggregateCompetition(saveGameId, competitionId);
        return aggregates.values().stream()
                .filter(a -> clubId.equals(a.clubId()) && a.goals() > 0)
                .max(Comparator.comparingInt(CompetitionPlayerAggregate::goals))
                .flatMap(a -> playerRepository.findById(a.playerId())
                        .map(p -> new MatchPreviewData.PreviewTopScorer(p.getName(), a.goals())))
                .orElse(null);
    }

    /**
     * The auxiliar's one-line read on the forecast, in pt-BR. Null for unremarkable weather —
     * the auxiliar comments when there is something to comment on.
     */
    static String weatherAdvice(Weather weather) {
        return switch (weather.condition()) {
            case CHUVA_FORTE ->
                "Chuva forte na véspera: o gramado vai ficar pesado, o jogo de passes perde efeito.";
            case CHUVA ->
                "Previsão de chuva: gramado molhado, cuidado com a saída de bola.";
            case VENTANIA ->
                "Muito vento previsto: a bola parada e os cruzamentos vão sofrer.";
            case ENSOLARADO, NUBLADO -> {
                if (weather.temperatureC() >= 32) {
                    yield "Calor forte previsto (" + weather.temperatureC()
                            + "°C): a pressão alta cobra caro no segundo tempo.";
                }
                if (weather.temperatureC() <= 10) {
                    yield "Frio previsto (" + weather.temperatureC()
                            + "°C): ritmo tende a cair, mas a equipe cansa menos.";
                }
                yield null;
            }
        };
    }

    private int averageOverall(List<Player> players) {
        return (int) Math.round(players.stream().mapToInt(Player::getOverall).average().orElse(0));
    }

    /** Serialize and write the news_item row (same seq = count + 1 pattern as every writer). */
    private void persist(UUID saveGameId, LocalDate eventDate, MatchPreviewData preview) {
        AdvanceResponse response = new AdvanceResponse(NEWS_TYPE, eventDate.toString(), null, null,
                List.of(), List.of(), List.of(), List.of(), null, 0, false, List.of(), List.of(),
                null, List.of(), List.of(), preview, null);
        try {
            String payload = objectMapper.writeValueAsString(response);
            int seq = newsItemRepository.countBySaveGameId(saveGameId) + 1;
            newsItemRepository.save(NewsItem.create(saveGameId, seq, eventDate, NEWS_TYPE, payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize match preview news item", e);
        }
    }
}
