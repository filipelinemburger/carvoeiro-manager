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
 * means the seed research found nobody — not that the club employs nobody (design §3.2a).
 */
@Service
public class StaffEffectsService {

    /**
     * Floor for an AI club whose role the research could not fill. Equivalent to the old flat
     * {@link StaffQuality#BOM}, so an unresearched AI club behaves exactly as it did before club
     * staff existed. Never applied to the managed club.
     */
    private static final double AI_BASELINE_AVERAGE = 14.0;

    private final StaffRepository staffRepository;

    public StaffEffectsService(StaffRepository staffRepository) {
        this.staffRepository = staffRepository;
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
     * The effective attribute average for a role: the club's real hired staff, or — when the slot
     * is empty — {@code null} for the managed club (neutral effect) and the baseline for an AI one.
     */
    private Double averageFor(UUID clubId, boolean managed, StaffRole role) {
        return staffRepository.findByClubIdAndRole(clubId, role)
                .map(Staff::attributeAverage)
                .orElseGet(() -> managed ? null : AI_BASELINE_AVERAGE);
    }

    /** Linear map anchored at (10, atRegular) and (17, atExcelente) — today's tier endpoints. */
    private static double lerp(double average, double atRegular, double atExcelente) {
        return atRegular + (atExcelente - atRegular) * (average - 10.0) / 7.0;
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
