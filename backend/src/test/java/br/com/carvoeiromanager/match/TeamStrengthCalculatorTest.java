package br.com.carvoeiromanager.match;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import br.com.carvoeiromanager.player.Marking;
import br.com.carvoeiromanager.player.Mentality;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRole;
import br.com.carvoeiromanager.player.Position;
import br.com.carvoeiromanager.player.Pressing;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TeamStrengthCalculatorTest {

    private final TeamStrengthCalculator calc = new TeamStrengthCalculator();

    private Player p(Position pos, int rating) {
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA",
                rating, rating, rating, rating, pos == Position.GK ? rating : 10, 70, rating);
    }

    /** A player whose full attribute profile has exactly the given values set (rest default 0). */
    private static Player fullProfilePlayer(Position pos, Map<Attribute, Integer> attrs) {
        Attributes.Builder builder = Attributes.builder();
        attrs.forEach(builder::set);
        return Player.create(UUID.randomUUID(), UUID.randomUUID(), "T", pos, 25, "BRA", builder.build(), 70);
    }

    private static Player fullProfilePlayer(Position pos, Attribute attr, int value) {
        return fullProfilePlayer(pos, Map.of(attr, value));
    }

    private static TacticalProfile profileWithTakers(List<Player> xi, UUID penaltyTakerId,
                                                      UUID freekickTakerId, UUID cornerTakerId) {
        Map<UUID, PlayerRole> roles = new HashMap<>();
        xi.forEach(pl -> roles.put(pl.getId(), PlayerRole.defaultFor(pl.getPosition())));
        return new TacticalProfile(Mentality.BALANCED, "4-4-2", Pressing.MEDIUM, Marking.ZONAL, roles,
                penaltyTakerId, freekickTakerId, cornerTakerId);
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
    void cornerThreatComesFromTheCornerTakersCrossing() {
        // The designated corner taker has CRUZAMENTO 80; someone else in the XI has 99. The
        // taker governs cornerThreat, not the squad's best crosser.
        List<Player> xi = standardXi();
        Player taker = fullProfilePlayer(Position.MID, Attribute.CRUZAMENTO, 80);
        Player betterCrosser = fullProfilePlayer(Position.ATT, Attribute.CRUZAMENTO, 99);
        List<Player> squad = new ArrayList<>(xi);
        squad.set(5, taker);
        squad.set(9, betterCrosser);
        TacticalProfile profile = profileWithTakers(squad, null, null, taker.getId());

        TeamMatchInput input = calc.compute(squad, profile);

        assertThat(input.cornerThreat()).isEqualTo(80 / 99.0, within(1e-9));
    }

    @Test
    void threatsFallBackToTheBestInTheXiWhenNoTakerIsSet() {
        List<Player> xi = standardXi();
        Player bestCrosser = fullProfilePlayer(Position.ATT, Attribute.CRUZAMENTO, 90);
        Player bestFreeKicker = fullProfilePlayer(Position.MID, Attribute.BOLA_PARADA, 85);
        List<Player> squad = new ArrayList<>(xi);
        squad.set(9, bestCrosser);
        squad.set(5, bestFreeKicker);
        TacticalProfile profile = profileWithTakers(squad, null, null, null);

        TeamMatchInput input = calc.compute(squad, profile);

        assertThat(input.cornerThreat()).isEqualTo(90 / 99.0, within(1e-9));
        assertThat(input.freeKickThreat()).isEqualTo(85 / 99.0, within(1e-9));
    }

    @Test
    void penaltyConversionComesFromThePenaltyTakersSetPieceSkill() {
        List<Player> xi = standardXi();

        // Designated taker with BOLA_PARADA 99 -> conversion is the top of the [0.70, 0.95] range.
        Player elite = fullProfilePlayer(Position.MID, Attribute.BOLA_PARADA, 99);
        List<Player> withTakerSquad = new ArrayList<>(xi);
        withTakerSquad.set(5, elite);
        TacticalProfile withTaker = profileWithTakers(withTakerSquad, elite.getId(), null, null);
        assertThat(calc.compute(withTakerSquad, withTaker).penaltyConversion())
                .isEqualTo(0.95, within(1e-9));

        // No taker set -> the best FINISHER's (non-GK) BOLA_PARADA feeds the formula — not the
        // squad's best BOLA_PARADA outright. bestFinisher has the top FINALIZACAO but a modest
        // BOLA_PARADA; bestFreeKicker has the top BOLA_PARADA but is not the best finisher.
        Player bestFinisher = fullProfilePlayer(Position.ATT,
                Map.of(Attribute.FINALIZACAO, 90, Attribute.BOLA_PARADA, 20));
        Player bestFreeKicker = fullProfilePlayer(Position.MID,
                Map.of(Attribute.FINALIZACAO, 50, Attribute.BOLA_PARADA, 99));
        List<Player> noTakerSquad = new ArrayList<>(xi);
        noTakerSquad.set(9, bestFinisher);
        noTakerSquad.set(5, bestFreeKicker);
        TacticalProfile noTaker = profileWithTakers(noTakerSquad, null, null, null);

        assertThat(calc.compute(noTakerSquad, noTaker).penaltyConversion())
                .isEqualTo(SetPieces.conversionChance(20), within(1e-9));
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
