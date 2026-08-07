package br.com.carvoeiromanager.competition;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.season.SeasonEvent;
import br.com.carvoeiromanager.season.SeasonEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public PhaseResolver(CompetitionPhaseRepository competitionPhaseRepository,
                         FixtureRepository fixtureRepository,
                         SeasonEventRepository seasonEventRepository,
                         StandingsService standingsService) {
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.fixtureRepository = fixtureRepository;
        this.seasonEventRepository = seasonEventRepository;
        this.standingsService = standingsService;
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
        log.info("Materialized {} for competition {} from {} qualifiers",
                next.getName(), current.getCompetitionId(), seeds.size());
        return true;
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
