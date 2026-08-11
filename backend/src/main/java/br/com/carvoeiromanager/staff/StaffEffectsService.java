package br.com.carvoeiromanager.staff;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The single place that turns a hired {@link Staff} row into a numeric effect. Every consumer
 * (training, injury risk, wage-demand discounts, sponsorship, match strength) calls through here
 * instead of touching {@link StaffRepository} directly.
 *
 * <p>Every club is read from its real rows. What differs is the fallback when a role is unfilled:
 * the managed club gets a neutral effect, because there an empty slot is a fact the player can act
 * on by hiring; an AI club gets {@link #AI_BASELINE_AVERAGE}, because there an empty slot only
 * means the seed research found nobody — not that the club employs nobody (design §3.2a) — unless
 * a {@link StaffVacancy} says the player emptied it themselves.
 */
@Service
public class StaffEffectsService {

    /**
     * Floor for an AI club whose role the research could not fill. Equivalent to the old flat
     * {@link StaffQuality#BOM}, so an unresearched AI club behaves exactly as it did before club
     * staff existed. Never applied to the managed club, and never applied to a slot the player
     * emptied — see {@link #averageFor}.
     */
    private static final double AI_BASELINE_AVERAGE = 14.0;

    private final StaffRepository staffRepository;
    private final StaffVacancyRepository staffVacancyRepository;

    public StaffEffectsService(StaffRepository staffRepository,
                               StaffVacancyRepository staffVacancyRepository) {
        this.staffRepository = staffRepository;
        this.staffVacancyRepository = staffVacancyRepository;
    }

    public int trainingBonusFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.PREPARADOR_FISICO);
        if (a == null) {
            return 0;
        }
        return (int) Math.max(0, Math.min(2, Math.round(lerp(a, 0.0, 2.0))));
    }

    public double injuryRiskMultiplierFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.FISIOTERAPEUTA);
        return a == null ? 1.0 : clamp(lerp(a, 1.0, 0.70), 0.65, 1.0);
    }

    public double wageDemandMultiplierFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.OLHEIRO);
        return a == null ? 1.0 : clamp(lerp(a, 1.0, 0.90), 0.88, 1.0);
    }

    public double sponsorshipMultiplierFor(UUID clubId, boolean managed) {
        Double a = averageFor(clubId, managed, StaffRole.ASSISTENTE);
        return a == null ? 1.0 : clamp(lerp(a, 1.0, 1.10), 1.0, 1.12);
    }

    /**
     * Head-coach strength multiplier for a club, in [0.96, 1.04]. The managed club has no TECNICO
     * row (see SaveGameService.seedClubStaff), so it always receives exactly 1.0 — the player's own
     * tactical decisions are already what the engine reads.
     *
     * <p>Unlike its four neighbours above, this method takes no {@code managed} flag and never
     * routes through {@link #AI_BASELINE_AVERAGE}. That baseline exists so an AI club's research
     * gap never reads as a penalty — but for a multiplier centred on 1.0, the non-penalising value
     * already IS 1.0: an unresearched club simply fields a league-average coach. Routing it through
     * 14.0 would instead produce 1.0057 — a systematic +0.57% strength bonus handed to every
     * unresearched AI club, which no research supports and which would perturb every
     * seeded-simulation result for no gain. TECNICO coverage is 28/31 clubs;
     * besides the managed club (always CRI, see SaveGameService.MANAGED_CLUB_SHORT_NAME, whose row
     * is withheld by the same managed-club convention), only CAM and CON ever reach the
     * {@code null} branch here — the two AI clubs the seed research never filled in.
     */
    public double coachStrengthMultiplierFor(UUID clubId) {
        Double a = rawAverageFor(clubId, StaffRole.TECNICO);
        return a == null ? 1.0 : clamp(lerp(a, 0.96, 1.04), 0.96, 1.04);
    }

    /**
     * The effective attribute average for a role, resolved in this order:
     * <ol>
     *   <li>a real staff row — its attribute average;</li>
     *   <li>else the managed club — {@code null}, i.e. a neutral effect: your own empty slot is a
     *       fact you fix by hiring;</li>
     *   <li>else a recorded {@link StaffVacancy} for that (club, role) — {@code null} as well;</li>
     *   <li>else — {@link #AI_BASELINE_AVERAGE}.</li>
     * </ol>
     *
     * <p>Steps 3 and 4 are the whole point of this method, and collapsing them is a real bug that
     * shipped once. The baseline models a RESEARCH GAP: nobody was authored into the seed for this
     * club's slot, so pretending the club has no physio at all would penalise them for our missing
     * data. A vacancy models something else entirely — a slot the PLAYER emptied by poaching its
     * holder. Both leave the {@code staff} table with no row, which is exactly why they used to be
     * indistinguishable, and treating the second as the first inverted the point of poaching: since
     * 46 of the 53 poachable rows are below 14.0, robbing a rival usually UPGRADED that rival to the
     * baseline — and permanently, because nothing ever refills the slot. A vacancy is real
     * knowledge, not a gap, so it earns the neutral effect a genuinely unstaffed club deserves.
     */
    private Double averageFor(UUID clubId, boolean managed, StaffRole role) {
        Double a = rawAverageFor(clubId, role);
        if (a != null) {
            return a;
        }
        if (managed) {
            return null;
        }
        return staffVacancyRepository.existsByClubIdAndRole(clubId, role) ? null : AI_BASELINE_AVERAGE;
    }

    /**
     * The hired staff's average with NO baseline fallback for anybody: {@code null} whenever the
     * slot is empty, managed club or not. Deliberately not named {@code averageFor} — it is not a
     * convenience overload with a defaulted {@code managed}, and reading it as one would silently
     * strip the AI baseline. Only {@link #coachStrengthMultiplierFor} wants this; see its javadoc.
     *
     * <p>It has no reason to consult {@link StaffVacancy} either: with no baseline to displace,
     * "empty" and "vacated" already produce the same neutral 1.0. (TECNICO is not hirable, so a
     * vacancy for that role cannot arise in the first place.)
     */
    private Double rawAverageFor(UUID clubId, StaffRole role) {
        return staffRepository.findByClubIdAndRole(clubId, role)
                .map(Staff::attributeAverage)
                .orElse(null);
    }

    /** Linear map anchored at (10, atRegular) and (17, atExcelente) — today's tier endpoints. */
    private static double lerp(double average, double atRegular, double atExcelente) {
        return atRegular + (atExcelente - atRegular) * (average - 10.0) / 7.0;
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
