package br.com.carvoeiromanager.contract;

import br.com.carvoeiromanager.player.OverallCalculator;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.seed.NewgenNames;
import br.com.carvoeiromanager.seed.SeedLoader;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates a clubless free agent (no contract, no wage). Deterministic for a
 * given RNG: draws first/last name, age, position, then attributes in a fixed
 * order. Quality bands are higher than newgens so the pool holds usable pros.
 */
@Component
public class FreeAgentGenerator {

    private final OverallCalculator overallCalculator;
    private final List<String> firstNames;
    private final List<String> lastNames;

    public FreeAgentGenerator(SeedLoader seedLoader, OverallCalculator overallCalculator) {
        this.overallCalculator = overallCalculator;
        NewgenNames names = seedLoader.loadNewgenNames();
        this.firstNames = names.firstNames();
        this.lastNames = names.lastNames();
    }

    public Player generate(UUID saveGameId, Random rng) {
        String name = firstNames.get(rng.nextInt(firstNames.size()))
                + " " + lastNames.get(rng.nextInt(lastNames.size()));
        int age = 22 + rng.nextInt(13); // 22..34
        Position position = Position.values()[rng.nextInt(Position.values().length)];

        int pace, finishing, passing, tackling, goalkeeping, stamina;
        switch (position) {
            case GK -> {
                pace = band(35, 18, rng); finishing = band(20, 12, rng); passing = band(40, 18, rng);
                tackling = band(25, 12, rng); goalkeeping = band(60, 18, rng); stamina = band(45, 18, rng);
            }
            case DEF -> {
                pace = band(48, 18, rng); finishing = band(28, 15, rng); passing = band(45, 18, rng);
                tackling = band(58, 18, rng); goalkeeping = band(1, 8, rng); stamina = band(50, 18, rng);
            }
            case MID -> {
                pace = band(48, 18, rng); finishing = band(40, 18, rng); passing = band(58, 18, rng);
                tackling = band(48, 18, rng); goalkeeping = band(1, 8, rng); stamina = band(52, 18, rng);
            }
            case ATT -> {
                pace = band(55, 18, rng); finishing = band(58, 18, rng); passing = band(45, 18, rng);
                tackling = band(28, 15, rng); goalkeeping = band(1, 8, rng); stamina = band(45, 18, rng);
            }
            default -> throw new IllegalStateException("Unknown position: " + position);
        }

        int overall = overallCalculator.compute(position, pace, finishing, passing,
                tackling, goalkeeping, stamina);
        // clubId null => free agent; wage stays 0 (not employed); contractYears stays 0.
        return Player.create(saveGameId, null, name, position, age, "Brasil",
                pace, finishing, passing, tackling, goalkeeping, stamina, overall);
    }

    private int band(int base, int span, Random rng) {
        return Math.min(99, base + rng.nextInt(span));
    }
}
