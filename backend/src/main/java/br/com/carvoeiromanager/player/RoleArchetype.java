package br.com.carvoeiromanager.player;

import static br.com.carvoeiromanager.player.DetailedPosition.*;
import static br.com.carvoeiromanager.player.RoleFocus.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The EAFC role dimension of a position (design spec §2.1): a tactical archetype (e.g.
 * "Box-to-box", "Ala ofensivo") valid for a specific subset of {@link DetailedPosition}s, each
 * with its own valid {@link RoleFocus} list (first = default) and an attack/defense attribute
 * weight profile that turns a player's {@link Attributes} into a contribution score.
 *
 * <p>Mirrors futbin.com/26/roles exactly (kept honest by {@code RoleArchetypeFutbinSyncTest}).
 * The 10 legacy-mapped archetypes (declared first) carry the exact weights of the retired
 * {@link PlayerRole}, so a lineup migrated onto its mapped archetype at default focus scores
 * identically to the old role-only model — the regression contract from the design spec.
 */
public enum RoleArchetype {
    // --- legacy-mapped (weights identical to the retired PlayerRole) ---
    GOALKEEPER("Goleiro", RoleFocus.DEFEND,
            Map.of(), Map.of(Attribute.REFLEXOS, 1.0)),
    STOPPER("Xerife", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.1, Attribute.FINALIZACAO, 0.1), Map.of(Attribute.DESARME, 1.0)),
    FULLBACK("Lateral", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.3, Attribute.PASSE, 0.2), Map.of(Attribute.DESARME, 0.8)),
    BALL_PLAYING_DEFENDER("Zagueiro construtor", RoleFocus.DEFEND,
            Map.of(Attribute.VELOCIDADE, 0.1, Attribute.PASSE, 0.4), Map.of(Attribute.DESARME, 0.85)),
    HOLDING("Primeiro volante", RoleFocus.DEFEND,
            Map.of(Attribute.PASSE, 0.3), Map.of(Attribute.DESARME, 0.8)),
    BOX_TO_BOX("Box-to-box", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.3, Attribute.PASSE, 0.3),
            Map.of(Attribute.DESARME, 0.5)),
    PLAYMAKER("Armador", RoleFocus.BALANCED,
            Map.of(Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.6), Map.of(Attribute.DESARME, 0.2)),
    POACHER("Oportunista", RoleFocus.ATTACK,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.FINALIZACAO, 0.8), Map.of()),
    TARGET_FORWARD("Pivô", RoleFocus.ATTACK,
            Map.of(Attribute.FINALIZACAO, 0.6, Attribute.PASSE, 0.2), Map.of(Attribute.DESARME, 0.1)),
    WINGER("Ponta", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.6, Attribute.FINALIZACAO, 0.2, Attribute.PASSE, 0.2),
            Map.of(Attribute.DESARME, 0.05)),

    // --- new archetypes (same value ranges as the legacy set) ---
    SWEEPER_KEEPER("Goleiro-líbero", RoleFocus.BALANCED,
            Map.of(), Map.of(Attribute.REFLEXOS, 0.85, Attribute.SAIDA_DE_GOL, 0.15)),
    BALL_PLAYING_KEEPER("Goleiro construtor", RoleFocus.BUILD_UP,
            Map.of(), Map.of(Attribute.REFLEXOS, 0.8, Attribute.JOGO_COM_OS_PES, 0.2)),
    WINGBACK("Ala", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.35, Attribute.CRUZAMENTO, 0.25), Map.of(Attribute.DESARME, 0.65)),
    FALSEBACK("Falso lateral", RoleFocus.BALANCED,
            Map.of(Attribute.PASSE, 0.35, Attribute.VISAO, 0.15),
            Map.of(Attribute.DESARME, 0.75, Attribute.MARCACAO, 0.1)),
    ATTACKING_WINGBACK("Ala ofensivo", RoleFocus.SUPPORT,
            Map.of(Attribute.VELOCIDADE, 0.4, Attribute.CRUZAMENTO, 0.3), Map.of(Attribute.DESARME, 0.5)),
    INVERTED_WINGBACK("Ala invertido", RoleFocus.BUILD_UP,
            Map.of(Attribute.PASSE, 0.4, Attribute.VISAO, 0.2), Map.of(Attribute.DESARME, 0.6)),
    DEFENDER("Zagueiro", RoleFocus.DEFEND,
            Map.of(), Map.of(Attribute.DESARME, 0.65, Attribute.MARCACAO, 0.35)),
    WIDE_BACK("Zagueiro ala", RoleFocus.DEFEND,
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.CRUZAMENTO, 0.1),
            Map.of(Attribute.DESARME, 0.7, Attribute.MARCACAO, 0.1)),
    DEEP_LYING_PLAYMAKER("Volante armador", RoleFocus.DEFEND,
            Map.of(Attribute.PASSE, 0.5, Attribute.VISAO, 0.3), Map.of(Attribute.DESARME, 0.45)),
    CENTRE_HALF("Terceiro zagueiro", RoleFocus.DEFEND,
            Map.of(), Map.of(Attribute.DESARME, 0.8, Attribute.MARCACAO, 0.2)),
    WIDE_HALF("Volante ala", RoleFocus.DEFEND,
            Map.of(Attribute.PASSE, 0.3, Attribute.CRUZAMENTO, 0.1), Map.of(Attribute.DESARME, 0.7)),
    BOX_CRASHER("Volante de chegada", RoleFocus.BALANCED,
            Map.of(Attribute.FINALIZACAO, 0.4, Attribute.CHUTE_LONGE, 0.2, Attribute.VELOCIDADE, 0.1),
            Map.of(Attribute.DESARME, 0.45)),
    HALF_WINGER("Meia-ala", RoleFocus.BALANCED,
            Map.of(Attribute.VELOCIDADE, 0.3, Attribute.PASSE, 0.3, Attribute.DRIBLE, 0.2),
            Map.of(Attribute.DESARME, 0.3)),
    SHADOW_STRIKER("Segundo atacante", RoleFocus.ATTACK,
            Map.of(Attribute.FINALIZACAO, 0.5, Attribute.POSICIONAMENTO, 0.2, Attribute.VELOCIDADE, 0.2),
            Map.of(Attribute.DESARME, 0.1)),
    CLASSIC_10("Camisa 10", RoleFocus.ATTACK,
            Map.of(Attribute.PASSE, 0.5, Attribute.VISAO, 0.3, Attribute.FINALIZACAO, 0.2), Map.of()),
    WIDE_PLAYMAKER("Armador aberto", RoleFocus.ATTACK,
            Map.of(Attribute.PASSE, 0.4, Attribute.VISAO, 0.2, Attribute.CRUZAMENTO, 0.2),
            Map.of(Attribute.DESARME, 0.1)),
    WIDE_MIDFIELDER("Meia de lado", RoleFocus.SUPPORT,
            Map.of(Attribute.CRUZAMENTO, 0.3, Attribute.PASSE, 0.2, Attribute.VELOCIDADE, 0.1),
            Map.of(Attribute.DESARME, 0.45)),
    INSIDE_FORWARD("Atacante por dentro", RoleFocus.BALANCED,
            Map.of(Attribute.FINALIZACAO, 0.4, Attribute.VELOCIDADE, 0.3, Attribute.DRIBLE, 0.2),
            Map.of(Attribute.DESARME, 0.05)),
    ADVANCED_FORWARD("Atacante móvel", RoleFocus.ATTACK,
            Map.of(Attribute.FINALIZACAO, 0.5, Attribute.VELOCIDADE, 0.35, Attribute.DRIBLE, 0.1), Map.of()),
    FALSE_9("Falso 9", RoleFocus.BUILD_UP,
            Map.of(Attribute.PASSE, 0.35, Attribute.FINALIZACAO, 0.3, Attribute.VISAO, 0.25), Map.of());

    private final String label;
    private final RoleFocus canonicalDefault;
    private final Map<Attribute, Double> attackWeights;
    private final Map<Attribute, Double> defenseWeights;

    RoleArchetype(String label, RoleFocus canonicalDefault, Map<Attribute, Double> attackWeights,
                  Map<Attribute, Double> defenseWeights) {
        this.label = label;
        this.canonicalDefault = canonicalDefault;
        this.attackWeights = attackWeights;
        this.defenseWeights = defenseWeights;
    }

    /** archetype → (valid position → focuses, default first). Mirrors futbin.com/26/roles exactly. */
    private static final Map<RoleArchetype, Map<DetailedPosition, List<RoleFocus>>> MATRIX = Map.ofEntries(
        Map.entry(GOALKEEPER, Map.of(GOLEIRO, List.of(DEFEND, BALANCED))),
        Map.entry(SWEEPER_KEEPER, Map.of(GOLEIRO, List.of(BALANCED, BUILD_UP))),
        Map.entry(BALL_PLAYING_KEEPER, Map.of(GOLEIRO, List.of(BUILD_UP))),
        Map.entry(FULLBACK, Map.of(
                LATERAL_DIREITO, List.of(BALANCED, DEFEND, VERSATILE),
                LATERAL_ESQUERDO, List.of(BALANCED, DEFEND, VERSATILE))),
        Map.entry(WINGBACK, Map.of(
                LATERAL_DIREITO, List.of(BALANCED, SUPPORT),
                LATERAL_ESQUERDO, List.of(BALANCED, SUPPORT))),
        Map.entry(FALSEBACK, Map.of(
                LATERAL_DIREITO, List.of(BALANCED, DEFEND),
                LATERAL_ESQUERDO, List.of(BALANCED, DEFEND))),
        Map.entry(ATTACKING_WINGBACK, Map.of(
                LATERAL_DIREITO, List.of(SUPPORT, ATTACK),
                LATERAL_ESQUERDO, List.of(SUPPORT, ATTACK))),
        Map.entry(INVERTED_WINGBACK, Map.of(
                LATERAL_DIREITO, List.of(BUILD_UP, ATTACK),
                LATERAL_ESQUERDO, List.of(BUILD_UP, ATTACK))),
        Map.entry(DEFENDER, Map.of(ZAGUEIRO, List.of(DEFEND, BALANCED))),
        Map.entry(STOPPER, Map.of(ZAGUEIRO, List.of(BALANCED, AGGRESSIVE))),
        Map.entry(BALL_PLAYING_DEFENDER, Map.of(ZAGUEIRO, List.of(DEFEND, BUILD_UP, AGGRESSIVE))),
        Map.entry(WIDE_BACK, Map.of(ZAGUEIRO, List.of(DEFEND, SUPPORT, AGGRESSIVE))),
        Map.entry(HOLDING, Map.of(
                VOLANTE, List.of(DEFEND, ROAMING, BALL_WINNING),
                MEIA_CENTRAL, List.of(DEFEND, BALL_WINNING))),
        Map.entry(DEEP_LYING_PLAYMAKER, Map.of(
                VOLANTE, List.of(DEFEND, ROAMING, BUILD_UP),
                MEIA_CENTRAL, List.of(DEFEND, BUILD_UP))),
        Map.entry(CENTRE_HALF, Map.of(VOLANTE, List.of(DEFEND))),
        Map.entry(WIDE_HALF, Map.of(VOLANTE, List.of(DEFEND, BUILD_UP))),
        Map.entry(BOX_CRASHER, Map.of(VOLANTE, List.of(BALANCED))),
        Map.entry(BOX_TO_BOX, Map.of(MEIA_CENTRAL, List.of(BALANCED, BALL_WINNING))),
        Map.entry(PLAYMAKER, Map.of(
                MEIA_CENTRAL, List.of(ATTACK, ROAMING),
                MEIA_ARMADOR, List.of(BALANCED, ROAMING, BUILD_UP))),
        Map.entry(HALF_WINGER, Map.of(
                MEIA_CENTRAL, List.of(BALANCED, ATTACK, SUPPORT),
                MEIA_ARMADOR, List.of(BALANCED, ATTACK, ROAMING))),
        Map.entry(SHADOW_STRIKER, Map.of(MEIA_ARMADOR, List.of(ATTACK))),
        Map.entry(CLASSIC_10, Map.of(MEIA_ARMADOR, List.of(ATTACK, WIDE, VERSATILE))),
        Map.entry(WINGER, Map.of(
                MEIA_DIREITA, List.of(BALANCED, ATTACK),
                MEIA_ESQUERDA, List.of(BALANCED, ATTACK),
                PONTA_DIREITA, List.of(BALANCED, ATTACK, VERSATILE),
                PONTA_ESQUERDA, List.of(BALANCED, ATTACK, VERSATILE))),
        Map.entry(WIDE_PLAYMAKER, Map.of(
                MEIA_DIREITA, List.of(ATTACK, BUILD_UP),
                MEIA_ESQUERDA, List.of(ATTACK, BUILD_UP),
                PONTA_DIREITA, List.of(ATTACK, BUILD_UP),
                PONTA_ESQUERDA, List.of(ATTACK, BUILD_UP))),
        Map.entry(WIDE_MIDFIELDER, Map.of(
                MEIA_DIREITA, List.of(SUPPORT, DEFEND, BUILD_UP),
                MEIA_ESQUERDA, List.of(SUPPORT, DEFEND, BUILD_UP))),
        Map.entry(INSIDE_FORWARD, Map.of(
                MEIA_DIREITA, List.of(BALANCED, ATTACK),
                MEIA_ESQUERDA, List.of(BALANCED, ATTACK),
                PONTA_DIREITA, List.of(BALANCED, ATTACK, ROAMING),
                PONTA_ESQUERDA, List.of(BALANCED, ATTACK, ROAMING))),
        Map.entry(ADVANCED_FORWARD, Map.of(ATACANTE, List.of(ATTACK, VERSATILE, SUPPORT))),
        Map.entry(TARGET_FORWARD, Map.of(ATACANTE, List.of(ATTACK, BALANCED, WIDE))),
        Map.entry(FALSE_9, Map.of(ATACANTE, List.of(BUILD_UP, ATTACK))),
        Map.entry(POACHER, Map.of(ATACANTE, List.of(ATTACK, SUPPORT, VERSATILE))));

    public String label() { return label; }

    public boolean validFor(DetailedPosition position) { return MATRIX.get(this).containsKey(position); }

    public Set<DetailedPosition> validPositions() { return MATRIX.get(this).keySet(); }

    public List<RoleFocus> focusesFor(DetailedPosition position) {
        return MATRIX.get(this).getOrDefault(position, List.of(canonicalDefault));
    }

    public RoleFocus defaultFocusFor(DetailedPosition position) { return focusesFor(position).get(0); }

    public RoleFocus canonicalDefaultFocus() { return canonicalDefault; }

    public Map<Attribute, Double> attackWeights() { return attackWeights; }

    public Map<Attribute, Double> defenseWeights() { return defenseWeights; }

    public double attackContribution(Player p) { return weighted(attackWeights, p.attributes()); }

    public double defenseContribution(Player p) { return weighted(defenseWeights, p.attributes()); }

    /**
     * Weighted sum of the given attribute weights against a player's attribute profile.
     * Copied verbatim from {@link PlayerRole#weighted} — {@code PlayerRole} still exists (and
     * still owns its own copy) until a later task swaps every caller onto this enum and deletes it.
     */
    public static double weighted(Map<Attribute, Double> weights, Attributes attrs) {
        double sum = 0.0;
        for (Map.Entry<Attribute, Double> e : weights.entrySet()) {
            sum += e.getValue() * attrs.get(e.getKey());
        }
        return sum;
    }

    /** The vanilla archetype a position falls back to when nothing is persisted/familiar. */
    public static RoleArchetype defaultFor(DetailedPosition position) {
        return switch (position) {
            case GOLEIRO -> GOALKEEPER;
            case ZAGUEIRO -> DEFENDER;
            case LATERAL_DIREITO, LATERAL_ESQUERDO -> FULLBACK;
            case VOLANTE -> HOLDING;
            case MEIA_CENTRAL -> BOX_TO_BOX;
            case MEIA_ARMADOR -> PLAYMAKER;
            case MEIA_DIREITA, MEIA_ESQUERDA, PONTA_DIREITA, PONTA_ESQUERDA -> WINGER;
            case ATACANTE -> ADVANCED_FORWARD;
        };
    }

    /** Wide creators boosted by AttackZone.LATERAIS (legacy: WINGER, FULL_BACK). */
    public boolean isWideCreator() {
        return this == WINGER || this == FULLBACK || this == WINGBACK
                || this == ATTACKING_WINGBACK || this == WIDE_PLAYMAKER || this == WIDE_MIDFIELDER;
    }

    /** Central creators boosted by AttackZone.CENTRO (legacy: PLAYMAKER, BOX_TO_BOX). */
    public boolean isCentralCreator() {
        return this == PLAYMAKER || this == BOX_TO_BOX || this == DEEP_LYING_PLAYMAKER
                || this == CLASSIC_10 || this == FALSE_9;
    }
}
