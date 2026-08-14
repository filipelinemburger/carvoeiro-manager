package br.com.carvoeiromanager.seed;

import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pyramid has to read as a pyramid. Each tier's mean club strength sits clearly below the one
 * above, measured exactly as the seed authoring measured it: a club's top-11 players by their mean
 * over all 28 attribute values.
 *
 * <p>Deliberately asserted on MEANS, not club by club. Série C's best clubs are stronger than Série
 * B's worst — <b>two</b> of them, Brusque 45.75 and Figueirense 45.20, sit above Londrina 44.97 —
 * and that overlap is correct: a strong third-tier side really is better than a struggling
 * second-tier one, and it is what makes promotion plausible rather than a cliff. A club-by-club
 * domination test would be false today. See spec §5.2a.
 *
 * <p><b>What this test cannot do:</b> a calibration test can never catch a wrong-player error. The
 * Série C batches seeded players who had already left their club — two in one batch, one who had
 * moved abroad in another — and not one of them moved any number here by a thousandth. Only
 * sourcing discipline catches those. A reader who takes this test as a guard on roster correctness
 * is trusting it for something it structurally cannot do.
 */
class TierCalibrationTest {

    private final SeedCareer career = new SeedLoader().loadCareer();

    private double top11(SeedClub club) {
        return club.players().stream()
                .map(this::playerAverage)
                .sorted(Comparator.reverseOrder())
                .limit(11)
                .mapToDouble(Double::doubleValue)
                .average().orElseThrow();
    }

    private double playerAverage(SeedPlayer p) {
        SeedAttributes a = p.attributes();
        int[] anchors = {p.pace(), p.finishing(), p.passing(), p.tackling(), p.goalkeeping(), p.stamina()};
        int sum = 0;
        for (int v : anchors) {
            sum += v;
        }
        sum += a.drible() + a.cruzamento() + a.cabeceio() + a.marcacao() + a.bolaParada()
                + a.chuteLonge() + a.visao() + a.posicionamento() + a.decisao() + a.compostura()
                + a.concentracao() + a.determinacao() + a.agressividade() + a.lideranca()
                + a.aceleracao() + a.forca() + a.resistencia() + a.equilibrio() + a.agilidade()
                + a.posicionamentoGk() + a.saidaDeGol() + a.jogoComOsPes();
        return sum / 28.0;
    }

    private double tierMean(String competitionName) {
        Map<String, SeedClub> pool = career.clubPool().stream()
                .collect(Collectors.toMap(SeedClub::shortName, Function.identity()));
        List<String> members = career.competitions().stream()
                .filter(c -> c.name().equals(competitionName))
                .findFirst().orElseThrow()
                .memberShortNames();
        return members.stream().map(pool::get).mapToDouble(this::top11).average().orElseThrow();
    }

    @Test
    void theThreeNationalTiersAreOrderedAndClearlySeparated() {
        double a = tierMean("Série A");
        double b = tierMean("Série B");
        double c = tierMean("Série C");

        // Each band is centred on the value measured at this commit (A 57.83, B 49.82, C 41.23)
        // with a ±1.5 tolerance. A failure here means the seed moved, not that the band is wrong —
        // the fix is to look at what changed in the seed, never to widen the band to fit it. These
        // bands replace ones drawn around mismeasured means, which left Série B 3.32 above its
        // floor and 0.68 under its ceiling; recentring on the true numbers is correcting a wrong
        // reference point, not relaxing a guard (±1.5 is tighter than the ±2.0 it replaces).
        //
        // ±1.5 can be this tight because for the GENERATED clubs every attribute comes from
        // `target + ROLE_DELTA[role]` in generate_squad.py, with position profiles normalised to
        // sum to zero — so renaming a player or changing his age, positions or roles is
        // numerically inert here. Only adding/removing a club or changing a `target` moves a tier
        // mean. The hand-authored tiers (Série A, Série B, Catarinense) carry no such guarantee,
        // and absorbing their edits is what the tolerance is really for.
        assertThat(a).as("Série A mean").isBetween(56.33, 59.33);
        assertThat(b).as("Série B mean").isBetween(48.32, 51.32);
        assertThat(c).as("Série C mean").isBetween(39.73, 42.73);

        // The rhythm of the ladder: each step down is a real drop, not a rounding difference.
        assertThat(a - b).as("A over B").isGreaterThan(6.0);
        assertThat(b - c).as("B over C").isGreaterThan(5.0);
    }

    @Test
    void serieCIsNotFlat() {
        // Derived attributes could easily produce twenty identical clubs. The tier needs a real
        // spread, or every promotion race is a coin flip.
        Map<String, SeedClub> pool = career.clubPool().stream()
                .collect(Collectors.toMap(SeedClub::shortName, Function.identity()));
        List<Double> strengths = career.competitions().stream()
                .filter(x -> x.name().equals("Série C"))
                .findFirst().orElseThrow()
                .memberShortNames().stream()
                .map(pool::get).map(this::top11).sorted().toList();

        assertThat(strengths.get(strengths.size() - 1) - strengths.get(0))
                .as("spread between the strongest and weakest Série C club")
                .isGreaterThan(5.0);
    }

    @Test
    void criciumaStillOutranksEverySerieCClub() {
        // The managed club is the strongest in Série B; it must not be seeded into a third tier
        // that could already beat it, or relegation would be meaningless.
        //
        // This is the one CLUB-BY-CLUB assertion in the file, and it is only safe because it
        // compares against the managed club specifically — Criciúma clears the strongest Série C
        // club by 10.45. The tier bands above are asserted on MEANS for the reason §5.2a gives,
        // and the two must never be conflated: generalising this into "every Série B club beats
        // every Série C club" would be writing something false (Brusque and Figueirense both beat
        // Londrina), and this slice's Global Constraints forbid exactly that assertion.
        Map<String, SeedClub> pool = career.clubPool().stream()
                .collect(Collectors.toMap(SeedClub::shortName, Function.identity()));
        double cri = top11(pool.get("CRI"));
        List<String> serieC = career.competitions().stream()
                .filter(x -> x.name().equals("Série C"))
                .findFirst().orElseThrow().memberShortNames();
        assertThat(serieC).allSatisfy(s ->
                assertThat(top11(pool.get(s))).as("%s vs Criciúma", s).isLessThan(cri));
    }
}
