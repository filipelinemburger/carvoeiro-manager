package br.com.carvoeiromanager.staff;

import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import br.com.carvoeiromanager.seed.SeedStaff;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rolls staff candidates on demand — never persisted. Slots are filled from a single market of
 * real people, whether or not they currently have a job:
 * <ul>
 *   <li>free professionals from the seed pool, and poachable rivals — real staff already employed
 *       at other clubs in the save, passed in as {@link EmployedStaff} so this class stays free of
 *       repository access. Both carry their authored attributes, with the tier derived from the
 *       average and the wage taken from the tier;</li>
 *   <li>generated newgen names, rolled inside a random tier band, only as a backstop when the
 *       market cannot fill the requested slots.</li>
 * </ul>
 * The market used to be two ordered tiers — pool first, rivals only once it ran dry. That made
 * poaching unreachable in practice: every role's free pool is far deeper than the three slots on
 * offer, and a club holds at most one staff member per role, so the pool could never run dry.
 * Merging the two means poaching frequency falls out of the seed data on its own — a role with
 * many employed and few free professionals (PREPARADOR_FISICO) is mostly poaching, one with the
 * opposite balance (OLHEIRO) is mostly free agents — with no tuning constant to pick.
 * <p>
 * Deterministic for a given (saveGameId, clubId, role, onDate, excludedNames, employedElsewhere):
 * re-browsing the same role on the same day always shows the same candidates. The market is built
 * in a fixed base order — free professionals by name, then poaching targets by id — before the one
 * seeded shuffle, so the order the caller's repository query happened to return does not leak in.
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
                                         LocalDate onDate, int count, Set<String> excludedNames,
                                         List<EmployedStaff> employedElsewhere) {
        long seed = saveGameId.getMostSignificantBits()
                ^ clubId.getMostSignificantBits()
                ^ role.ordinal()
                ^ onDate.toEpochDay();
        Random rng = new Random(seed);

        List<StaffCandidate> candidates = new ArrayList<>();

        // Names already spoken for: everyone the caller excluded (already hired at the hiring
        // club), plus each candidate as it is offered.
        Set<String> taken = new HashSet<>(excludedNames);

        // One market. Free professionals and rivals' employees compete for the same slots, so a
        // poaching target's odds are simply its share of the market for that role. Each half is
        // put in a stable base order first — the seed pool by name, the employed list by id,
        // because it arrives straight from a repository query with no ordering guarantee — and the
        // halves are concatenated in a fixed order, leaving the single seeded shuffle below as the
        // only source of variation.
        List<StaffCandidate> market = new ArrayList<>();
        poolByRole.getOrDefault(role, List.of()).stream()
                .filter(pro -> !taken.contains(pro.name()))
                .sorted(Comparator.comparing(SeedStaff::name))
                .forEach(pro -> market.add(asFreeProfessional(pro)));
        employedElsewhere.stream()
                .filter(employed -> !taken.contains(employed.name()))
                .sorted(Comparator.comparing(EmployedStaff::staffId))
                .forEach(employed -> market.add(asPoachingTarget(employed)));
        Collections.shuffle(market, rng);
        for (StaffCandidate offered : market) {
            if (candidates.size() >= count) {
                break;
            }
            // taken.add, not a plain add: nothing in the two halves guarantees they name distinct
            // people. A seed test asserts no name sits in both staff-pool.json and
            // club-staff.json, but that assertion is all that stands between here and offering
            // the same person twice, so do not lean on it.
            if (taken.add(offered.name())) {
                candidates.add(offered);
            }
        }

        StaffQuality[] tiers = StaffQuality.values();

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
            candidates.add(StaffCandidate.free(name, quality, quality.monthlyWage(), primary, secondary));
        }
        return candidates;
    }

    /** A seed-pool professional: authored attributes kept, tier from their average, wage by tier. */
    private static StaffCandidate asFreeProfessional(SeedStaff pro) {
        StaffQuality quality = qualityOf(pro.primaryAttribute(), pro.secondaryAttribute());
        return StaffCandidate.free(pro.name(), quality, quality.monthlyWage(),
                pro.primaryAttribute(), pro.secondaryAttribute());
    }

    /**
     * A rival's employee, priced exactly like a free professional — the source club's own stored
     * quality is never consulted, so the same attributes always cost the same wherever they work.
     */
    private static StaffCandidate asPoachingTarget(EmployedStaff employed) {
        StaffQuality quality = qualityOf(employed.primaryAttribute(), employed.secondaryAttribute());
        return new StaffCandidate(employed.name(), quality, quality.monthlyWage(),
                employed.primaryAttribute(), employed.secondaryAttribute(),
                employed.staffId(), employed.clubName());
    }

    private static StaffQuality qualityOf(int primaryAttribute, int secondaryAttribute) {
        return StaffQuality.forAverage((primaryAttribute + secondaryAttribute) / 2.0);
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
