package br.com.carvoeiromanager.staff;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The single place that turns a hired {@link Staff} row into a numeric effect. Every consumer
 * (training, injury risk, wage-demand discounts, sponsorship) calls through here instead of
 * touching {@link StaffRepository} directly. Non-managed (AI-controlled) clubs always receive the
 * fixed baseline attribute average of 14.0 (equivalent to the old flat {@link StaffQuality#BOM})
 * — mirrors {@code TrainingService.heuristicFocusFor}'s no-roster AI-parity shape.
 */
@Service
public class StaffEffectsService {

    /** Fixed attribute average granted to every AI-controlled club (equivalent to the old flat BOM). */
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
     * The effective attribute average for a role: the managed club's hired staff (or {@code null}
     * for a neutral effect when the role is unfilled), or the fixed AI baseline for every other club.
     */
    private Double averageFor(UUID clubId, boolean managed, StaffRole role) {
        if (!managed) {
            return AI_BASELINE_AVERAGE;
        }
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
