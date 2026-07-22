package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffEffectsService;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * Weekly, manager-directed training. Once per in-game ISO week ({@link #processWeek}, guarded by
 * {@code SaveGame.lastTrainingWeek} exactly like {@code FinanceService.processMonth} guards
 * monthly costs), every contracted senior player accumulates progress toward +1 on their focus
 * anchor attribute; a point fires every {@value #WEEKS_PER_SEASON} units so the expected
 * per-season total matches the old one-shot rollover bonus. The managed club's Preparador
 * Físico (when hired) turns the week's notable events into a {@link TrainingWeekReport}.
 */
@Component
public class TrainingService {

    /** Nominal training weeks per season; one progress unit = one week of bonus-1 training. */
    static final int WEEKS_PER_SEASON = 40;

    // Weekly injury risk = the old per-season risk (LEVE 0, NORMAL 0.03, INTENSA 0.08) / 40.
    private static final double NORMAL_WEEKLY_RISK = 0.03 / WEEKS_PER_SEASON;
    private static final double INTENSA_WEEKLY_RISK = 0.08 / WEEKS_PER_SEASON;

    /** EXCELENTE physios flag players within half a point of their next evolution. */
    private static final int NEAR_EVOLUTION_THRESHOLD = WEEKS_PER_SEASON / 2;

    /** INTENSA training at this age or older draws a risk warning in the report. */
    private static final int VETERAN_AGE = 30;

    private final OverallCalculator overallCalculator;
    private final StaffEffectsService staffEffectsService;
    private final SaveGameRepository saveGameRepository;
    private final PlayerRepository playerRepository;
    private final StaffRepository staffRepository;

    public TrainingService(OverallCalculator overallCalculator, StaffEffectsService staffEffectsService,
                           SaveGameRepository saveGameRepository, PlayerRepository playerRepository,
                           StaffRepository staffRepository) {
        this.overallCalculator = overallCalculator;
        this.staffEffectsService = staffEffectsService;
        this.saveGameRepository = saveGameRepository;
        this.playerRepository = playerRepository;
        this.staffRepository = staffRepository;
    }

    /**
     * Applies one weekly training tick to every contracted senior player in the save and returns
     * the Preparador Físico's report of the managed club's notable events — or null when the
     * event date's ISO week was already processed, no physio is hired, or nothing notable
     * happened (training itself always runs on a new week).
     */
    @Transactional
    public TrainingWeekReport processWeek(UUID saveGameId, LocalDate eventDate) {
        SaveGame save = saveGameRepository.findById(saveGameId).orElseThrow();
        int week = eventDate.get(IsoFields.WEEK_BASED_YEAR) * 100
                + eventDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        // Self-idempotency guard: one tick per ISO week, mirroring FinanceService.processMonth.
        // Assumes the season stays within one ISO week-based year (the calendar runs Jan-Nov and
        // rollover resets the guard): a late-December date falling in week 1 of the NEXT
        // week-based year would produce a smaller int and be silently skipped.
        if (week <= save.getLastTrainingWeek()) {
            return null;
        }
        save.setLastTrainingWeek(week);
        saveGameRepository.save(save);

        final UUID managedClubId = save.getManagedClubId();
        // Seeded per (save, week) and consumed in sorted-player order: reproducible rolls.
        Random rng = new Random(saveGameId.getMostSignificantBits() ^ week);
        // Both coach bonuses are constant for the whole week — resolve once, not per player.
        int managedBonus = staffEffectsService.trainingBonusFor(managedClubId, true);
        int aiBonus = staffEffectsService.trainingBonusFor(managedClubId, false);

        Optional<Staff> physio = staffRepository
                .findByClubIdAndRole(managedClubId, StaffRole.PREPARADOR_FISICO);
        StaffQuality reportQuality = physio.map(Staff::getQuality).orElse(null);

        // Notable-event buckets in report priority order.
        List<TrainingWeekReport.Entry> evolutions = new ArrayList<>();
        List<TrainingWeekReport.Entry> injuries = new ArrayList<>();
        List<TrainingWeekReport.Entry> ceilings = new ArrayList<>();
        List<TrainingWeekReport.Entry> risky = new ArrayList<>();
        List<TrainingWeekReport.Entry> nearEvolutions = new ArrayList<>();

        List<Player> players = playerRepository.findBySaveGameId(saveGameId).stream()
                .filter(p -> p.getClubId() != null && !p.isYouthProspect())
                .sorted(Comparator.comparing(Player::getId))
                .toList();

        for (Player p : players) {
            boolean managed = p.getClubId().equals(managedClubId);
            Attribute focus = managed ? p.getTrainingFocus() : heuristicFocusFor(p.getPosition());
            TrainingIntensity intensity = managed ? p.getTrainingIntensity() : TrainingIntensity.NORMAL;
            if (focus == null) {
                continue; // "not training" leaves the player untouched
            }
            int bonus = intensityBonus(intensity) + (managed ? managedBonus : aiBonus);

            int before = anchorValue(p, focus);
            int after = before;
            boolean atCeiling = false;
            int progress = p.getTrainingProgress() + bonus;
            while (progress >= WEEKS_PER_SEASON) {
                progress -= WEEKS_PER_SEASON;
                if (after < 99) {
                    after++;
                } else {
                    atCeiling = true; // the point is lost at the cap
                }
            }
            p.setTrainingProgress(progress);
            if (after != before) {
                applyAnchor(p, focus, after);
            }

            double risk = switch (intensity) {
                case LEVE -> 0.0;
                case NORMAL -> NORMAL_WEEKLY_RISK;
                case INTENSA -> INTENSA_WEEKLY_RISK;
            };
            int layoff = 0;
            if (risk > 0 && rng.nextDouble() < risk) {
                layoff = 1 + rng.nextInt(2);
                p.addInjury(layoff);
            }

            if (managed && reportQuality != null) {
                if (after != before) {
                    evolutions.add(new TrainingWeekReport.Entry(p.getName(),
                            evolutionMessage(reportQuality, p.getName(), focus, before, after)));
                }
                if (layoff > 0) {
                    injuries.add(new TrainingWeekReport.Entry(p.getName(),
                            p.getName() + " se lesionou durante os treinos (fora por " + layoff + " partida(s))."));
                }
                if (atCeiling) {
                    ceilings.add(new TrainingWeekReport.Entry(p.getName(),
                            ceilingMessage(reportQuality, p.getName(), focus)));
                }
                if (intensity == TrainingIntensity.INTENSA && p.getAge() >= VETERAN_AGE) {
                    risky.add(new TrainingWeekReport.Entry(p.getName(),
                            "Treino intenso para " + p.getName() + " (" + p.getAge() + " anos) traz alto risco de lesão."));
                }
                if (reportQuality == StaffQuality.EXCELENTE && after == before && !atCeiling
                        && progress >= NEAR_EVOLUTION_THRESHOLD) {
                    nearEvolutions.add(new TrainingWeekReport.Entry(p.getName(),
                            p.getName() + " está perto de evoluir " + focus.label() + "."));
                }
            }
        }
        playerRepository.saveAll(players);

        if (reportQuality == null) {
            return null;
        }
        int cap = switch (reportQuality) {
            case REGULAR -> 3;
            case BOM -> 6;
            case EXCELENTE -> 10;
        };
        List<TrainingWeekReport.Entry> entries = new ArrayList<>();
        entries.addAll(evolutions);
        entries.addAll(injuries);
        entries.addAll(ceilings);
        entries.addAll(risky);
        entries.addAll(nearEvolutions);
        if (entries.isEmpty()) {
            return null;
        }
        if (entries.size() > cap) {
            entries = new ArrayList<>(entries.subList(0, cap));
        }
        return new TrainingWeekReport(physio.get().getName(), reportQuality.name(), List.copyOf(entries));
    }

    /** Fixed position-based focus used for every AI-controlled club's players. */
    public static Attribute heuristicFocusFor(Position position) {
        return switch (position) {
            case GK -> Attribute.REFLEXOS;
            case DEF -> Attribute.DESARME;
            case MID -> Attribute.PASSE;
            case ATT -> Attribute.FINALIZACAO;
        };
    }

    private static int intensityBonus(TrainingIntensity intensity) {
        return switch (intensity) {
            case LEVE -> 1;
            case NORMAL -> 2;
            case INTENSA -> 3;
        };
    }

    private static String evolutionMessage(StaffQuality quality, String name, Attribute focus,
                                           int before, int after) {
        return switch (quality) {
            case REGULAR -> name + " evoluiu nos treinos.";
            case BOM -> name + " evoluiu em " + focus.label() + ".";
            case EXCELENTE -> name + " evoluiu em " + focus.label() + " (" + before + " → " + after + ").";
        };
    }

    private static String ceilingMessage(StaffQuality quality, String name, Attribute focus) {
        return quality == StaffQuality.REGULAR
                ? name + " não tem mais o que evoluir — considere trocar o foco."
                : name + " não tem mais o que evoluir em " + focus.label() + " — considere trocar o foco.";
    }

    /** Works for every trainable focus: the six legacy getters are just {@code Attributes} views. */
    private static int anchorValue(Player p, Attribute focus) {
        return p.attribute(focus);
    }

    /**
     * The six legacy anchors recompute overall via {@link OverallCalculator} (unchanged
     * behavior). The other six focuses just overwrite their own attribute — overall is
     * anchor-based by design and never moves for them.
     */
    private void applyAnchor(Player p, Attribute focus, int value) {
        switch (focus) {
            case VELOCIDADE, FINALIZACAO, PASSE, DESARME, REFLEXOS, FOLEGO -> {
                int pace = p.getPace();
                int finishing = p.getFinishing();
                int passing = p.getPassing();
                int tackling = p.getTackling();
                int goalkeeping = p.getGoalkeeping();
                int stamina = p.getStamina();
                switch (focus) {
                    case VELOCIDADE -> pace = value;
                    case FINALIZACAO -> finishing = value;
                    case PASSE -> passing = value;
                    case DESARME -> tackling = value;
                    case REFLEXOS -> goalkeeping = value;
                    case FOLEGO -> stamina = value;
                    default -> throw new IllegalStateException("unreachable");
                }
                int overall = overallCalculator.compute(p.getPosition(),
                        pace, finishing, passing, tackling, goalkeeping, stamina);
                p.evolveAttributes(pace, finishing, passing, tackling, goalkeeping, stamina, overall);
            }
            default -> p.setAttribute(focus, value);
        }
    }

}
