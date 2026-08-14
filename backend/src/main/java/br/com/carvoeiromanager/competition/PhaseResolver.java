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

    /**
     * Order of a knockout phase's two reserved rounds: {@code (event_date, round)} — never date
     * alone. {@code reserved.get(0)} becomes leg 1 and {@code reserved.get(1)} leg 2, so this
     * comparator decides which leg is which.
     *
     * <p><strong>The {@code round} tiebreak is load-bearing; do not "simplify" it away.</strong>
     * A managed competition's rounds sit at strict 5-day intervals and could never tie, but a
     * background competition's units are dated <em>proportionally</em> across the managed season's
     * window ({@code SeasonCalendarGenerator.generateBackground}) and may legitimately share a
     * date — which is also why
     * {@code BackgroundCompetitionService.BY_DATE_COMPETITION_ROUND} exists. On a tie a date-only
     * sort falls back to arbitrary DB order, so leg 1 can be stamped onto the LATER round. The
     * calendar then plays "leg 2" first, {@code FixtureSimulator.needsExtraTime} sees an unplayed
     * leg 1 and skips the shootout, and a level tie reaches {@link TieResolver#winner} with no
     * penalties recorded. That throws out of {@code resolveDue} and rolls the advance back —
     * including every {@code markProcessed()} — so the next advance replays the identical failure
     * and the save is permanently unadvanceable with a 500.
     */
    private static final Comparator<SeasonEvent> RESERVED_ROUND_ORDER =
            Comparator.comparing(SeasonEvent::getEventDate)
                    .thenComparing(SeasonEvent::getRound);

    private final CompetitionPhaseRepository competitionPhaseRepository;
    private final CompetitionRepository competitionRepository;
    private final FixtureRepository fixtureRepository;
    private final SeasonEventRepository seasonEventRepository;
    private final StandingsService standingsService;
    private final ClubRepository clubRepository;
    private final NewsItemRepository newsItemRepository;
    private final ObjectMapper objectMapper;
    private final FixtureScheduler fixtureScheduler = new FixtureScheduler();

    public PhaseResolver(CompetitionPhaseRepository competitionPhaseRepository,
                         CompetitionRepository competitionRepository,
                         FixtureRepository fixtureRepository,
                         SeasonEventRepository seasonEventRepository,
                         StandingsService standingsService,
                         ClubRepository clubRepository,
                         NewsItemRepository newsItemRepository,
                         ObjectMapper objectMapper) {
        this.competitionPhaseRepository = competitionPhaseRepository;
        this.competitionRepository = competitionRepository;
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
            try {
                seeds = TieResolver.tiesOf(played).stream().map(TieResolver::winner).toList();
            } catch (IllegalStateException undecided) {
                // Deliberately NOT swallowed: an undecided tie in a phase the caller has already
                // confirmed complete means the bracket is corrupt, and continuing would seed the
                // next phase from a lie. But TieResolver only knows the tie id, and this throws
                // out of advance() into a 500 the player cannot get past — so re-throw naming the
                // competition and both phases, which is the whole diagnosis in the log line.
                throw new IllegalStateException("Cannot seed " + next.getName() + " of "
                        + competitionName(current.getCompetitionId()) + ": phase " + current.getName()
                        + " (" + current.getId() + ") is complete but a tie is undecided — "
                        + undecided.getMessage(), undecided);
            }
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
     *
     * <p>The message names the competition. It has to: background phased competitions resolve
     * their own knockouts now, so a single season crowns both the state championship and Série C,
     * and an unqualified "X é o campeão!" left the player with two cards they could not tell
     * apart. The news is deliberately NOT gated to the managed club's own competitions —
     * promotion and relegation hang on Série C's result, so the tier below is genuinely the
     * player's business.
     *
     * <p><strong>Why "{competition}: {club} é o campeão!" and not "campeão da {competition}".</strong>
     * The natural phrasing needs a gendered contraction — <em>da</em> Série C but <em>do</em>
     * Campeonato Catarinense — and nothing in the data says which. {@link Competition} stores a
     * free-text {@code name} straight from the seed JSON and a {@code type} that is a format tag,
     * not a grammatical gender. Guessing from the ending is not safe either: "Série" ends in -e
     * and is feminine, so the usual -a/-o rule already needs a special case on the very first
     * name, and every future seed entry ("Copa…", "Torneio…", "Troféu…") is another chance to
     * silently emit text a Brazilian player reads as broken. Leading with the competition and a
     * colon is idiomatic sports-headline Portuguese, needs no article at all, and puts the
     * distinguishing word first — which is what the reader is scanning for.
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
            // The caller has already confirmed this phase complete, so every leg should be played
            // and every level tie should carry a shootout: reaching here is a real bug, not a
            // benign race. Staying a no-op is still right — a missing headline must not brick the
            // season the way the propagated variant at the seeding call site would — but silence
            // is not, so this logs the same diagnosis that one throws.
            log.warn("No CHAMPION news for final phase {} ({}) of {}: {}", finalPhase.getName(),
                    finalPhase.getId(), competitionName(finalPhase.getCompetitionId()),
                    notYetDecided.getMessage());
            return;
        }
        String winnerName = clubRepository.findById(winnerId).map(Club::getName).orElse("Campeão");
        String headline = winnerName + " é o campeão!";
        // A competition row that cannot be found is not a reason to drop the announcement: fall
        // back to the bare headline rather than prefixing a "null:" the player would have to read.
        // competition_phase.competition_id is an FK, so a miss here is a data-integrity fault and
        // has to leave a trace even though the news still goes out.
        String competitionName = competitionRepository.findById(finalPhase.getCompetitionId())
                .map(Competition::getName).orElse(null);
        if (competitionName == null) {
            log.warn("Competition {} has no row while announcing the champion of phase {} ({}); "
                    + "emitting the headline unqualified", finalPhase.getCompetitionId(),
                    finalPhase.getName(), finalPhase.getId());
        }
        String message = competitionName == null ? headline : competitionName + ": " + headline;
        persistNews(save.getId(), save.getCurrentDate(), "CHAMPION", message);
    }

    /** A competition's name for log lines and exception messages; its id when the row is missing. */
    private String competitionName(UUID competitionId) {
        return competitionRepository.findById(competitionId)
                .map(Competition::getName).orElseGet(() -> String.valueOf(competitionId));
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
                .sorted(RESERVED_ROUND_ORDER).toList();

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
