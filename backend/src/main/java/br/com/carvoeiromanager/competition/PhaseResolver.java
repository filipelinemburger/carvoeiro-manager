package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Fills in a knockout phase's fixtures once the phase before it has finished. Knockout rounds
 * are reserved (dated, no fixtures) at generation because fixtures.home_club_id is NOT NULL and
 * the participants are unknown until the preceding phase ends.
 *
 * <p>Pairings are a pure function of the previous phase's standings — no RNG, no wall-clock — so
 * a replayed season produces an identical bracket. Materialization is idempotent: it runs only
 * when the target phase has no fixtures yet.
 */
@Service
public class PhaseResolver {

    private static final Logger log = LoggerFactory.getLogger(PhaseResolver.class);

    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final FixtureRepository fixtureRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final StandingsService standingsService;
    private final ClubRepository clubRepository;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public PhaseResolver(CompetitionPhaseRepository competitionPhaseRepository,
                         FixtureRepository fixtureRepository,
                         SeasonEventRepository seasonEventRepository,
                         StandingsService standingsService,
                         ClubRepository clubRepository,
                         NewsItemRepository newsItemRepository,
                         ObjectMapper objectMapper) {
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.fixtureRepository = fixtureRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.standingsService = standingsService;
        this.clubRepository = clubRepository;
        this.newsItemRepository = newsItemRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * If {@code processedEvent} was the last unprocessed event of its phase, materialize the
     * next phase's fixtures. Returns true when fixtures were created.
     */
    @Transactional
    public boolean resolveIfPhaseComplete(SaveGame save, SeasonEvent processedEvent) {
        UUID phaseId = processedEvent.getPhaseId();
        if (phaseId == null) {
            return false; // a plain league round; nothing to resolve
        }
        List<CompetitionPhase> phases = competitionPhaseRepository
                .findByCompetitionIdOrderByOrdinalAsc(processedEvent.getCompetitionId());

        CompetitionPhase current = phases.stream().filter(p -> p.getId().equals(phaseId))
                .findFirst().orElse(null);
        if (current == null || !phaseComplete(save.getId(), current, processedEvent.getId())) {
            return false;
        }
        CompetitionPhase next = phases.stream()
                .filter(p -> p.getOrdinal() == current.getOrdinal() + 1).findFirst().orElse(null);

        // A round-robin phase just ended: tell the managed club whether it goes through to
        // whatever comes next, or is done for the season. Only meaningful when there IS a next
        // phase to qualify into — a standalone round-robin competition has no such transition.
        if (current.getKind() == PhaseKind.ROUND_ROBIN && next != null) {
            emitQualificationNews(save, current, next);
        }

        // A knockout phase just ended with nothing after it: that was the final, and its winner
        // is the champion.
        if (current.getKind() == PhaseKind.KNOCKOUT && next == null) {
            emitChampionNews(save, current);
        }

        if (next == null || next.getKind() != PhaseKind.KNOCKOUT) {
            return false;
        }
        if (!fixtureRepository.findBySaveGameIdAndPhaseId(save.getId(), next.getId()).isEmpty()) {
            return false; // already materialized — idempotent
        }

        List<UUID> seeds;
        if (current.getKind() == PhaseKind.ROUND_ROBIN) {
            // The current phase is confirmed complete above (every one of its MATCH events is
            // processed), so every one of its fixtures is already played by this point — an
            // unbounded table (asOfDate = null) is the correct, complete seeding table. Passing
            // this round's own date would exclude the very round that just finished it, since
            // computeStandings' asOfDate bound is strictly-before (see StandingsService).
            seeds = standingsService
                    .computeStandings(save, current.getCompetitionId(), current.getId(), null)
                    .stream().map(StandingRow::clubId).limit(current.getQualifiers()).toList();
        } else {
            // Winners of the phase just finished, in that phase's tie order — which is itself
            // seeded best-to-worst, so index 0 is the best surviving seed. knockoutPairings then
            // re-pairs best against worst: with four semifinalists, index 0 plays index 3 and
            // index 1 plays index 2, exactly as the spec requires.
            List<Fixture> played =
                    fixtureRepository.findBySaveGameIdAndPhaseId(save.getId(), current.getId());
            seeds = TieResolver.tiesOf(played).stream().map(TieResolver::winner).toList();
        }

        materialize(save.getId(), current.getCompetitionId(), next, seeds);
        emitKnockoutDrawNews(save, next);
        log.info("Materialized {} for competition {} from {} qualifiers",
                next.getName(), current.getCompetitionId(), seeds.size());
        return true;
    }

    /**
     * PHASE_QUALIFIED / PHASE_ELIMINATED for the managed club, once its round-robin phase ends.
     * No-op if the save has no managed club yet, or the managed club is not a member of this
     * competition at all (neither is a bug — just nothing to announce).
     */
    private void emitQualificationNews(SaveGame save, CompetitionPhase current, CompetitionPhase next) {
        UUID managedClubId = save.getManagedClubId();
        if (managedClubId == null) {
            return;
        }
        List<StandingRow> table = standingsService
                .computeStandings(save, current.getCompetitionId(), current.getId(), null);
        int index = -1;
        for (int i = 0; i < table.size(); i++) {
            if (table.get(i).clubId().equals(managedClubId)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return; // the managed club is not part of this competition
        }
        boolean qualified = current.getQualifiers() != null && index < current.getQualifiers();
        if (qualified) {
            persistNews(save.getId(), save.getCurrentDate(), "PHASE_QUALIFIED",
                    "Classificado para a fase " + next.getName() + "!");
        } else {
            persistNews(save.getId(), save.getCurrentDate(), "PHASE_ELIMINATED",
                    "Eliminado na " + current.getName() + ".");
        }
    }

    /**
     * KNOCKOUT_DRAW naming the managed club's opponent, once a knockout phase's ties have just
     * been materialized. No-op if the managed club isn't in this phase's bracket at all (a
     * competition the save doesn't otherwise follow, or a save with no managed club yet).
     */
    private void emitKnockoutDrawNews(SaveGame save, CompetitionPhase phase) {
        UUID managedClubId = save.getManagedClubId();
        if (managedClubId == null) {
            return;
        }
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndPhaseId(save.getId(), phase.getId());
        for (KnockoutTie tie : TieResolver.tiesOf(fixtures)) {
            UUID opponentId;
            if (managedClubId.equals(tie.higherSeedClubId())) {
                opponentId = tie.lowerSeedClubId();
            } else if (managedClubId.equals(tie.lowerSeedClubId())) {
                opponentId = tie.higherSeedClubId();
            } else {
                continue;
            }
            String opponentName = clubRepository.findById(opponentId).map(Club::getName).orElse("adversário");
            persistNews(save.getId(), save.getCurrentDate(), "KNOCKOUT_DRAW",
                    "Sorteio da " + phase.getName() + ": o adversário será " + opponentName + ".");
            return;
        }
    }

    /**
     * CHAMPION once the final's winner is known. {@code finalPhase} is confirmed complete by the
     * caller, so {@link TieResolver#winner} should not throw here — but a defensive catch keeps
     * this a no-op rather than a 500 if that invariant is ever wrong.
     */
    private void emitChampionNews(SaveGame save, CompetitionPhase finalPhase) {
        List<Fixture> fixtures = fixtureRepository.findBySaveGameIdAndPhaseId(save.getId(), finalPhase.getId());
        List<KnockoutTie> ties = TieResolver.tiesOf(fixtures);
        if (ties.isEmpty()) {
            return;
        }
        UUID winnerId;
        try {
            winnerId = TieResolver.winner(ties.get(0));
        } catch (IllegalStateException notYetDecided) {
            return;
        }
        String winnerName = clubRepository.findById(winnerId).map(Club::getName).orElse("Campeão");
        persistNews(save.getId(), save.getCurrentDate(), "CHAMPION", winnerName + " é o campeão!");
    }

    /**
     * Writes a phase-transition news item. {@code news_item.type} is {@code VARCHAR(16)} — every
     * type passed in here (PHASE_QUALIFIED=15, PHASE_ELIMINATED=16, KNOCKOUT_DRAW=13, CHAMPION=8)
     * fits. The message lands in {@code competitionName}, the only free-text String field on
     * {@link AdvanceResponse} — same sparse-payload convention as WatchlistNotifier /
     * PreContractService: every other field is empty/null.
     *
     * <p>The frontend does NOT render {@code competitionName} generically — an earlier version of
     * this comment claimed it did, and it was wrong. AdvanceEventCard reads it only under
     * {@code type === 'MATCH' && round !== null}, so these four types needed their own branch
     * there, plus entries in newsTitle/newsIcon and in the AdvanceResponse type union. Without
     * those the item persisted correctly and rendered as an empty card.
     *
     * <p>{@code date} must be populated: NewsPage formats it directly, and a null shows a blank
     * date. Every other news writer in this codebase passes {@code eventDate.toString()}.
     * Reuses the same {@code seq = count + 1} pattern the other writers use.
     */
    private void persistNews(UUID saveGameId, LocalDate eventDate, String type, String message) {
        AdvanceResponse response = new AdvanceResponse(type, eventDate.toString(), message, null,
                List.of(), List.of(), List.of(), List.of(), null, 0, false, List.of(), List.of(),
                null, List.of(), List.of(), null, null);
        try {
            String payload = objectMapper.writeValueAsString(response);
            int seq = newsItemRepository.countBySaveGameId(saveGameId) + 1;
            newsItemRepository.save(NewsItem.create(saveGameId, seq, eventDate, type, payload));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize phase-transition news item", e);
        }
    }

    /** True when every MATCH event of this phase other than the one just handled is processed. */
    private boolean phaseComplete(UUID saveGameId, CompetitionPhase phase, UUID justProcessedId) {
        return seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> phase.getId().equals(e.getPhaseId()))
                .allMatch(e -> e.isProcessed() || e.getId().equals(justProcessedId));
    }

    /**
     * Create one tie per pairing across the phase's reserved rounds. The higher seed hosts the
     * second leg. Fixture dates come from the reserved events, which are already dated.
     */
    private void materialize(UUID saveGameId, UUID competitionId, CompetitionPhase phase, List<UUID> seeds) {
        List<SeasonEvent> reserved = seasonEventRepository.findBySaveGameId(saveGameId).stream()
                .filter(e -> phase.getId().equals(e.getPhaseId()))
                .sorted(Comparator.comparing(SeasonEvent::getEventDate)).toList();

        // A two-legged knockout round needs exactly two reserved (dated, fixture-less) rounds —
        // one per leg. Anything else means the calendar generator and this resolver have drifted
        // out of sync; fail loudly here rather than an unguarded reserved.get(1) throwing a bare
        // IndexOutOfBoundsException deep inside the loop below.
        if (reserved.size() != 2) {
            throw new IllegalStateException("Knockout phase " + phase.getName() + " (" + phase.getId()
                    + ") has " + reserved.size() + " reserved round(s); a two-legged tie needs exactly 2");
        }

        List<int[]> pairings = fixtureScheduler.knockoutPairings(seeds.size());
        List<Fixture> created = new ArrayList<>();
        for (int order = 0; order < pairings.size(); order++) {
            int[] pairing = pairings.get(order);
            UUID higher = seeds.get(pairing[0]);
            UUID lower = seeds.get(pairing[1]);
            UUID tieId = UUID.randomUUID();
            created.add(leg(saveGameId, competitionId, phase, reserved.get(0), tieId, order, 1, lower, higher));
            created.add(leg(saveGameId, competitionId, phase, reserved.get(1), tieId, order, 2, higher, lower));
        }
        fixtureRepository.saveAll(created);
    }

    private Fixture leg(UUID saveGameId, UUID competitionId, CompetitionPhase phase, SeasonEvent event,
                        UUID tieId, int tieOrder, int leg, UUID homeClubId, UUID awayClubId) {
        Fixture fixture = Fixture.create(saveGameId, competitionId, event.getRound(),
                homeClubId, awayClubId, event.getEventDate());
        fixture.setPhaseId(phase.getId());
        fixture.setTieId(tieId);
        fixture.setTieOrder(tieOrder);
        fixture.setLeg(leg);
        return fixture;
    }
}
