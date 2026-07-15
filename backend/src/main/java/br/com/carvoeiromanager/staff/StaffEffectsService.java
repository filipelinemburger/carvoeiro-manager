package br.com.carvoeiromanager.staff;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The single place that turns a hired {@link Staff} row into a numeric effect. Every consumer
 * (training, injury risk, wage-demand discounts, sponsorship) calls through here instead of
 * touching {@link StaffRepository} directly. Non-managed (AI-controlled) clubs never have a
 * {@link Staff} row and always receive the fixed baseline, equivalent to {@link StaffQuality#BOM}
 * in every role — mirrors {@code TrainingService.heuristicFocusFor}'s no-roster AI-parity shape.
 */
@Service
public class StaffEffectsService {

    private final StaffRepository staffRepository;

    public StaffEffectsService(StaffRepository staffRepository) {
        this.staffRepository = staffRepository;
    }

    public int trainingBonusFor(UUID clubId, boolean managed) {
        return trainingBonus(qualityFor(clubId, managed, StaffRole.PREPARADOR_FISICO));
    }

    public double injuryRiskMultiplierFor(UUID clubId, boolean managed) {
        return injuryRiskMultiplier(qualityFor(clubId, managed, StaffRole.FISIOTERAPEUTA));
    }

    public double wageDemandMultiplierFor(UUID clubId, boolean managed) {
        return wageDemandMultiplier(qualityFor(clubId, managed, StaffRole.OLHEIRO));
    }

    public double sponsorshipMultiplierFor(UUID clubId, boolean managed) {
        return sponsorshipMultiplier(qualityFor(clubId, managed, StaffRole.ASSISTENTE));
    }

    /**
     * Resolves the effective quality for a role: the managed club's hired staff (or {@code null}
     * if none), or the fixed AI baseline ({@link StaffQuality#BOM}) for every other club.
     */
    private StaffQuality qualityFor(UUID clubId, boolean managed, StaffRole role) {
        if (!managed) {
            return StaffQuality.BOM;
        }
        Optional<Staff> hired = staffRepository.findByClubIdAndRole(clubId, role);
        return hired.map(Staff::getQuality).orElse(null);
    }

    private int trainingBonus(StaffQuality quality) {
        if (quality == null) {
            return 0;
        }
        return switch (quality) {
            case REGULAR -> 0;
            case BOM -> 1;
            case EXCELENTE -> 2;
        };
    }

    private double injuryRiskMultiplier(StaffQuality quality) {
        if (quality == null) {
            return 1.0;
        }
        return switch (quality) {
            case REGULAR -> 1.0;
            case BOM -> 0.85;
            case EXCELENTE -> 0.7;
        };
    }

    private double wageDemandMultiplier(StaffQuality quality) {
        if (quality == null) {
            return 1.0;
        }
        return switch (quality) {
            case REGULAR -> 1.0;
            case BOM -> 0.95;
            case EXCELENTE -> 0.9;
        };
    }

    private double sponsorshipMultiplier(StaffQuality quality) {
        if (quality == null) {
            return 1.0;
        }
        return switch (quality) {
            case REGULAR -> 1.0;
            case BOM -> 1.05;
            case EXCELENTE -> 1.1;
        };
    }
}
