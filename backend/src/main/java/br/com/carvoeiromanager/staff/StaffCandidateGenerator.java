package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedStaff;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rolls staff candidates on demand — never persisted. Real professionals from the seed pool
 * are offered first (their authored attributes fixed, tier derived from the average, wage by
 * tier); when the role's unhired pool runs dry the remaining slots are filled with generated
 * newgen names whose attributes are rolled inside the tier band. Deterministic for a given
 * (saveGameId, clubId, role, onDate, excludedNames): re-browsing the same role on the same
 * day always shows the same candidates.
 */
@Component
public class StaffCandidateGenerator {

    private final List<String> firstNames;
    private final List<String> lastNames;
    private final Map<StaffRole, List<SeedStaff>> poolByRole;

    public StaffCandidateGenerator(SeedLoader seedLoader) {
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
        this.poolByRole = seedLoader.loadStaffPool().stream()
                .collect(Collectors.groupingBy(SeedStaff::role));
    }

    /** Redraw budget before falling back to a suffixed name; the pool has 682 combinations. */
    private static final int MAX_NAME_DRAWS = 200;

    public List<StaffCandidate> generate(UUID saveGameId, UUID clubId, StaffRole role,
                                         LocalDate onDate, int count, Set<String> excludedNames) {
        long seed = saveGameId.getMostSignificantBits()
                ^ clubId.getMostSignificantBits()
                ^ role.ordinal()
                ^ onDate.toEpochDay();
        Random rng = new Random(seed);

        List<StaffCandidate> candidates = new ArrayList<>();

        List<SeedStaff> available = poolByRole.getOrDefault(role, List.of()).stream()
                .filter(s -> !excludedNames.contains(s.name()))
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(available, rng);
        for (int i = 0; i < count && i < available.size(); i++) {
            SeedStaff pro = available.get(i);
            double average = (pro.primaryAttribute() + pro.secondaryAttribute()) / 2.0;
            StaffQuality quality = StaffQuality.forAverage(average);
            candidates.add(new StaffCandidate(pro.name(), quality, quality.monthlyWage(),
                    pro.primaryAttribute(), pro.secondaryAttribute()));
        }

        StaffQuality[] tiers = StaffQuality.values();
        // Names already spoken for: the authored candidates just offered, plus everyone the
        // caller excluded (already-hired staff, and the authored pool when it is exhausted).
        Set<String> taken = new HashSet<>(excludedNames);
        candidates.forEach(c -> taken.add(c.name()));

        while (candidates.size() < count) {
            // Generated names are drawn from the same Brazilian first/last name pools the real
            // staff come from (31 x 22 = 682 combinations), so a draw can land exactly on a real
            // staff member's name — "Henrique Lima" is in both. Redraw rather than offer a
            // duplicate or someone the caller explicitly excluded.
            String name = null;
            for (int attempt = 0; attempt < MAX_NAME_DRAWS && name == null; attempt++) {
                String drawn = firstNames.get(rng.nextInt(firstNames.size()))
                        + " " + lastNames.get(rng.nextInt(lastNames.size()));
                if (taken.add(drawn)) {
                    name = drawn;
                }
            }
            if (name == null) {
                // The 682-name space is far larger than any realistic candidate count, but never
                // spin forever: fall back to a suffixed name that cannot collide.
                String base = firstNames.get(rng.nextInt(firstNames.size()))
                        + " " + lastNames.get(rng.nextInt(lastNames.size()));
                int suffix = 2;
                while (!taken.add(base + " " + suffix)) {
                    suffix++;
                }
                name = base + " " + suffix;
            }
            StaffQuality quality = tiers[rng.nextInt(tiers.length)];
            int primary = rollAttribute(quality, rng);
            int secondary = rollAttribute(quality, rng);
            candidates.add(new StaffCandidate(name, quality, quality.monthlyWage(), primary, secondary));
        }
        return candidates;
    }

    /** Uniform roll inside the tier's band: REGULAR 8-12, BOM 12-16, EXCELENTE 15-19. */
    private static int rollAttribute(StaffQuality quality, Random rng) {
        return switch (quality) {
            case REGULAR -> 8 + rng.nextInt(5);
            case BOM -> 12 + rng.nextInt(5);
            case EXCELENTE -> 15 + rng.nextInt(5);
        };
    }
}
