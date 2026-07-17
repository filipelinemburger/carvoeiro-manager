package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Rolls staff candidates on demand — never persisted, unlike the free-agent pool (seeded once
 * from real data and only ever shrinks). Deterministic for a given (saveGameId, clubId, role,
 * onDate): the offer is stable within a game-day and changes only as the season advances, so
 * re-browsing the same role on the same day always shows the same three candidates.
 */
@Component
public class StaffCandidateGenerator {

    private final List<String> firstNames;
    private final List<String> lastNames;

    public StaffCandidateGenerator(SeedLoader seedLoader) {
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
    }

    public List<StaffCandidate> generate(UUID saveGameId, UUID clubId, StaffRole role,
                                         LocalDate onDate, int count) {
        long seed = saveGameId.getMostSignificantBits()
                ^ clubId.getMostSignificantBits()
                ^ role.ordinal()
                ^ onDate.toEpochDay();
        Random rng = new Random(seed);

        List<StaffCandidate> candidates = new ArrayList<>();
        StaffQuality[] tiers = StaffQuality.values();
        for (int i = 0; i < count; i++) {
            String name = firstNames.get(rng.nextInt(firstNames.size()))
                    + " " + lastNames.get(rng.nextInt(lastNames.size()));
            StaffQuality quality = tiers[rng.nextInt(tiers.length)];
            candidates.add(new StaffCandidate(name, quality, wageFor(quality)));
        }
        return candidates;
    }

    private BigDecimal wageFor(StaffQuality quality) {
        return switch (quality) {
            case REGULAR -> new BigDecimal("20000.00");
            case BOM -> new BigDecimal("45000.00");
            case EXCELENTE -> new BigDecimal("80000.00");
        };
    }
}
