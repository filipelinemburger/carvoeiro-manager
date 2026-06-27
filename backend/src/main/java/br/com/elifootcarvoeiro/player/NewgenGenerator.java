package br.com.elifootcarvoeiro.player;

import br.com.elifootcarvoeiro.finance.FinanceCalculator;
import br.com.elifootcarvoeiro.seed.NewgenNames;
import br.com.elifootcarvoeiro.seed.SeedLoader;
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

        int pace, finishing, passing, tackling, goalkeeping, stamina;
        switch (position) {
            case GK -> {
                pace = band(30, 20, rng); finishing = band(20, 15, rng); passing = band(35, 20, rng);
                tackling = band(25, 15, rng); goalkeeping = band(45, 20, rng); stamina = band(40, 20, rng);
            }
            case DEF -> {
                pace = band(40, 20, rng); finishing = band(25, 15, rng); passing = band(35, 20, rng);
                tackling = band(45, 20, rng); goalkeeping = band(1, 8, rng); stamina = band(40, 20, rng);
            }
            case MID -> {
                pace = band(40, 20, rng); finishing = band(35, 20, rng); passing = band(45, 20, rng);
                tackling = band(40, 20, rng); goalkeeping = band(1, 8, rng); stamina = band(45, 20, rng);
            }
            case ATT -> {
                pace = band(45, 20, rng); finishing = band(45, 20, rng); passing = band(40, 20, rng);
                tackling = band(25, 15, rng); goalkeeping = band(1, 8, rng); stamina = band(35, 20, rng);
            }
            default -> throw new IllegalStateException("Unknown position: " + position);
        }

        int overall = overallCalculator.compute(position, pace, finishing, passing,
                tackling, goalkeeping, stamina);
        Player newgen = Player.create(saveGameId, clubId, name, position, age, "Brasil",
                pace, finishing, passing, tackling, goalkeeping, stamina, overall);
        newgen.setWage(financeCalculator.weeklyWage(overall, age));
        return newgen;
    }

    private int band(int base, int span, Random rng) {
        return Math.min(99, base + rng.nextInt(span));
    }
}
