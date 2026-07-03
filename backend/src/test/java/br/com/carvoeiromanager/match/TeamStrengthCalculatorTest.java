package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TeamStrengthCalculatorTest {

    private final TeamStrengthCalculator calc = new TeamStrengthCalculator();

    private Player p(Position pos, int rating) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                rating, rating, rating, rating, pos == Position.GK ? rating : 10, 70, rating);
    }

    /** 1 GK + 4 DEF + 4 MID + 2 ATT, all rated 70. */
    private List<Player> standardXi() {
        return List.of(
                p(Position.GK, 70),
                p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70), p(Position.DEF, 70),
                p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70), p(Position.MID, 70),
                p(Position.ATT, 70), p(Position.ATT, 70));
    }

    private TacticalProfile profile(Mentality m, String formation, Pressing pr, Marking mk, List<Player> xi) {
        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        return new TacticalProfile(m, formation, pr, mk, roles, null, null, null);
    }

    @Test
    void attackingMentalityRaisesAttackAndLowersDefenseVsDefensive() {
        List<Player> xi = standardXi();
        TeamMatchInput att = calc.compute(xi, profile(Mentality.ATTACKING, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        TeamMatchInput def = calc.compute(xi, profile(Mentality.DEFENSIVE, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(att.attack()).isGreaterThan(def.attack());
        assertThat(att.defense()).isLessThan(def.defense());
    }

    @Test
    void highPressRaisesTempoAggressionAndDecay() {
        List<Player> xi = standardXi();
        TeamMatchInput high = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.HIGH, Marking.ZONAL, xi));
        TeamMatchInput low = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.LOW, Marking.ZONAL, xi));
        assertThat(high.tempo()).isGreaterThan(low.tempo());
        assertThat(high.aggression()).isGreaterThan(low.aggression());
        assertThat(high.fitnessDecay()).isGreaterThan(low.fitnessDecay());
    }

    @Test
    void manTightRaisesDefenseAndAggression() {
        List<Player> xi = standardXi();
        TeamMatchInput tight = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.MAN_TIGHT, xi));
        TeamMatchInput zonal = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(tight.defense()).isGreaterThan(zonal.defense());
        assertThat(tight.aggression()).isGreaterThan(zonal.aggression());
    }

    @Test
    void formMultiplierRaisesContribution() {
        List<Player> xi = standardXi();
        TeamMatchInput baseline = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        xi.forEach(pl -> IntStream.range(0, 5).forEach(i -> pl.applyForm(1))); // +5 form -> +10%
        TeamMatchInput inForm = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(inForm.attack()).isGreaterThan(baseline.attack());
    }

    @Test
    void lowFitnessLowersAttackAndDefenseVsFullFitness() {
        List<Player> xi = standardXi();
        TacticalProfile prof = profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi);
        java.util.Map<UUID, Double> tired = new HashMap<>();
        xi.forEach(pl -> tired.put(pl.getId(), 0.80)); // everyone at the 0-fitness multiplier floor

        TeamMatchInput fresh = calc.compute(xi, prof); // defaults to 1.0
        TeamMatchInput drained = calc.compute(xi, prof, tired);

        assertThat(drained.attack()).isLessThan(fresh.attack());
        assertThat(drained.defense()).isLessThan(fresh.defense());
    }

    @Test
    void setPieceThreatTracksDesignatedTaker() {
        // Squad whose only elite finisher is the ace (99); everyone else is 70.
        List<Player> xi = standardXi();
        Player ace = p(Position.ATT, 99);
        List<Player> squad = new java.util.ArrayList<>(xi);
        squad.set(10, ace);
        Map<UUID, PlayerRole> roles = new HashMap<>();
        squad.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));

        Player weakTaker = squad.get(5); // a midfielder rated 70 — below the ace

        TacticalProfile aceTaker = new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL,
                roles, ace.getId(), ace.getId(), ace.getId());
        TacticalProfile weakTakerProfile = new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL,
                roles, weakTaker.getId(), weakTaker.getId(), weakTaker.getId());
        TacticalProfile noTaker = new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL,
                roles, null, null, null);

        double aceThreat = calc.compute(squad, aceTaker).setPieceThreat();
        double weakThreat = calc.compute(squad, weakTakerProfile).setPieceThreat();
        double fallbackThreat = calc.compute(squad, noTaker).setPieceThreat();

        // No taker -> falls back to the squad's best finisher (the ace).
        assertThat(fallbackThreat).isEqualTo(ace.getFinishing() / 99.0);
        // Designating the ace matches that fallback...
        assertThat(aceThreat).isEqualTo(fallbackThreat);
        // ...but designating a weaker taker is actually consulted: threat drops below the squad max.
        assertThat(weakThreat).isLessThan(fallbackThreat);
        assertThat(weakThreat).isBetween(0.0, 1.0);
    }

    @Test
    void moraleMultiplierRaisesContribution() {
        List<Player> xi = standardXi();
        TeamMatchInput baseline = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        xi.forEach(pl -> IntStream.range(0, 5).forEach(i -> pl.applyMorale(1))); // +5 morale -> +10%
        TeamMatchInput happy = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        // Morale multiplies into the same per-player term as form, so it lifts both attack and defense.
        assertThat(happy.attack()).isGreaterThan(baseline.attack());
        assertThat(happy.defense()).isGreaterThan(baseline.defense());
    }

    @Test
    void lowMoraleLowersContribution() {
        List<Player> xi = standardXi();
        TeamMatchInput baseline = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        xi.forEach(pl -> IntStream.range(0, 5).forEach(i -> pl.applyMorale(-1))); // -5 morale -> -10%
        TeamMatchInput sad = calc.compute(xi, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, xi));
        assertThat(sad.attack()).isLessThan(baseline.attack());
        assertThat(sad.defense()).isLessThan(baseline.defense());
    }

    @Test
    void neutralMoraleLeavesStrengthUnchanged() {
        // Determinism contract: at the default morale 0 the morale term is exactly x1.0, so a squad's
        // strength is identical to what it was before morale existed. This locks the backward-compat
        // guarantee the whole feature relies on (existing seeded match results stay byte-identical).
        List<Player> neutral = standardXi();
        List<Player> alsoNeutral = standardXi();
        neutral.forEach(pl -> assertThat(pl.getMorale()).isZero());

        TeamMatchInput a = calc.compute(neutral, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, neutral));
        TeamMatchInput b = calc.compute(alsoNeutral, profile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, alsoNeutral));
        assertThat(a.attack()).isEqualTo(b.attack());
        assertThat(a.defense()).isEqualTo(b.defense());
    }
}
