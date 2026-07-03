package br.com.carvoeiromanager.player;

import br.com.carvoeiromanager.finance.FinanceCalculator;
import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates a replacement youth player ("newgen") when a player retires, so a
 * club's squad size stays constant. Deterministic for a given RNG: draws name,
 * age, then attributes in a fixed order. The youth-growth curve develops them
 * over subsequent seasons.
 */
@Component
public class NewgenGenerator {

    private final OverallCalculator overallCalculator;
    private final FinanceCalculator financeCalculator;
    private final List<String> firstNames;
    private final List<String> lastNames;

    public NewgenGenerator(SeedLoader seedLoader, OverallCalculator overallCalculator,
                           FinanceCalculator financeCalculator) {
        this.overallCalculator = overallCalculator;
        this.financeCalculator = financeCalculator;
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
    }

    public Player generate(UUID saveGameId, UUID clubId, Position position, Random rng) {
        String name = firstNames.get(rng.nextInt(firstNames.size()))
                + " " + lastNames.get(rng.nextInt(lastNames.size()));
        int age = 16 + rng.nextInt(4); // 16..19
        Attrs a = drawAttributes(position, rng);
        int overall = overallCalculator.compute(position, a.pace(), a.finishing(), a.passing(),
                a.tackling(), a.goalkeeping(), a.stamina());
        Player newgen = Player.create(saveGameId, clubId, name, position, age, "Brasil",
                a.pace(), a.finishing(), a.passing(), a.tackling(), a.goalkeeping(), a.stamina(), overall);
        newgen.setWage(financeCalculator.weeklyWage(overall, age));
        return newgen;
    }

    /** Generates a youth-academy prospect: young (15-17), flagged, with a hidden potential ceiling. */
    public Player generateProspect(UUID saveGameId, UUID clubId, Position position, Random rng) {
        String name = firstNames.get(rng.nextInt(firstNames.size()))
                + " " + lastNames.get(rng.nextInt(lastNames.size()));
        int age = 15 + rng.nextInt(3); // 15..17
        Attrs a = drawAttributes(position, rng);
        int overall = overallCalculator.compute(position, a.pace(), a.finishing(), a.passing(),
                a.tackling(), a.goalkeeping(), a.stamina());
        // Low-skewed headroom (nextDouble squared): most prospects modest, a few gems. Floor of 4.
        int headroom = 4 + (int) (rng.nextDouble() * rng.nextDouble() * 32);
        int potential = Math.min(92, overall + headroom);
        Player prospect = Player.create(saveGameId, clubId, name, position, age, "Brasil",
                a.pace(), a.finishing(), a.passing(), a.tackling(), a.goalkeeping(), a.stamina(), overall);
        prospect.setWage(financeCalculator.weeklyWage(overall, age));
        prospect.setContractYears(0);
        prospect.markAsProspect(potential);
        return prospect;
    }

    private record Attrs(int pace, int finishing, int passing, int tackling, int goalkeeping, int stamina) { }

    private Attrs drawAttributes(Position position, Random rng) {
        return switch (position) {
            case GK -> new Attrs(band(30, 20, rng), band(20, 15, rng), band(35, 20, rng),
                    band(25, 15, rng), band(45, 20, rng), band(40, 20, rng));
            case DEF -> new Attrs(band(40, 20, rng), band(25, 15, rng), band(35, 20, rng),
                    band(45, 20, rng), band(1, 8, rng), band(40, 20, rng));
            case MID -> new Attrs(band(40, 20, rng), band(35, 20, rng), band(45, 20, rng),
                    band(40, 20, rng), band(1, 8, rng), band(45, 20, rng));
            case ATT -> new Attrs(band(45, 20, rng), band(45, 20, rng), band(40, 20, rng),
                    band(25, 15, rng), band(1, 8, rng), band(35, 20, rng));
        };
    }

    private int band(int base, int span, Random rng) {
        return Math.min(99, base + rng.nextInt(span));
    }
}
