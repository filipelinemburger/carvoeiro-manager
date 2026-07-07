package br.com.carvoeiromanager.player;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A per-player behaviour (instruction) layered on a {@link PlayerRole}. Each behaviour applies
 * an attribute-weight delta on top of the role's base weights. The <b>first behaviour declared
 * for a role is its default</b> and carries empty deltas, so its effective weights equal the
 * base role exactly — any lineup that doesn't pick a variant scores identically to the
 * role-only model (the determinism contract). Variants shift emphasis (e.g. Atacante played as
 * a Falso 9, or a Ponta cutting inside).
 */
public enum RoleBehaviour {
    // Goleiro
    GOLEIRO(PlayerRole.GOALKEEPER, "Padrão", Map.of(), Map.of()),

    // Zagueiro / Zagueiro construtor
    ZAGUEIRO_MARCADOR(PlayerRole.STOPPER, "Marcador", Map.of(), Map.of()),
    ZAGUEIRO_CONSTRUTOR(PlayerRole.BALL_PLAYING_DEF, "Construtor", Map.of(), Map.of()),

    // Lateral: equilibrado (default) / ofensivo
    LATERAL_EQUILIBRADO(PlayerRole.FULL_BACK, "Equilibrado", Map.of(), Map.of()),
    LATERAL_OFENSIVO(PlayerRole.FULL_BACK, "Ofensivo",
            Map.of(Attribute.VELOCIDADE, 0.2, Attribute.CRUZAMENTO, 0.2),
            Map.of(Attribute.DESARME, -0.2)),

    // Volante
    VOLANTE_PROTECAO(PlayerRole.HOLDING_MID, "Proteção", Map.of(), Map.of()),

    // Box to box
    BOX_TO_BOX(PlayerRole.BOX_TO_BOX, "Box to box", Map.of(), Map.of()),

    // Armador: clássico (default) / avançado
    ARMADOR_CLASSICO(PlayerRole.PLAYMAKER, "Clássico", Map.of(), Map.of()),
    ARMADOR_AVANCADO(PlayerRole.PLAYMAKER, "Avançado",
            Map.of(Attribute.FINALIZACAO, 0.2, Attribute.CHUTE_LONGE, 0.2),
            Map.of()),

    // Atacante: finalizador (default) / falso 9
    FINALIZADOR(PlayerRole.POACHER, "Finalizador", Map.of(), Map.of()),
    FALSO_9(PlayerRole.POACHER, "Falso 9",
            Map.of(Attribute.PASSE, 0.3, Attribute.VISAO, 0.2, Attribute.FINALIZACAO, -0.3),
            Map.of()),

    // Centroavante de referência
    PIVO(PlayerRole.TARGET_MAN, "Pivô", Map.of(), Map.of()),

    // Ponta: aberto (default) / por dentro
    PONTA_ABERTO(PlayerRole.WINGER, "Aberto", Map.of(), Map.of()),
    PONTA_POR_DENTRO(PlayerRole.WINGER, "Por dentro",
            Map.of(Attribute.FINALIZACAO, 0.2, Attribute.DRIBLE, 0.2, Attribute.CRUZAMENTO, -0.1),
            Map.of());

    private final PlayerRole role;
    private final String label;
    private final Map<Attribute, Double> attackDelta;
    private final Map<Attribute, Double> defenseDelta;

    RoleBehaviour(PlayerRole role, String label, Map<Attribute, Double> attackDelta, Map<Attribute, Double> defenseDelta) {
        this.role = role;
        this.label = label;
        this.attackDelta = attackDelta;
        this.defenseDelta = defenseDelta;
    }

    public PlayerRole role() { return role; }

    public String label() { return label; }

    public boolean isValidFor(PlayerRole r) { return role == r; }

    /** Base role attack weights with this behaviour's delta applied (never negative). */
    public Map<Attribute, Double> effectiveAttackWeights() {
        return merge(role.attackWeights(), attackDelta);
    }

    /** Base role defence weights with this behaviour's delta applied (never negative). */
    public Map<Attribute, Double> effectiveDefenseWeights() {
        return merge(role.defenseWeights(), defenseDelta);
    }

    private static Map<Attribute, Double> merge(Map<Attribute, Double> base, Map<Attribute, Double> delta) {
        if (delta.isEmpty()) {
            return base;
        }
        EnumMap<Attribute, Double> out = new EnumMap<>(Attribute.class);
        out.putAll(base);
        for (Map.Entry<Attribute, Double> e : delta.entrySet()) {
            double v = out.getOrDefault(e.getKey(), 0.0) + e.getValue();
            if (v > 0.0) {
                out.put(e.getKey(), v);
            } else {
                out.remove(e.getKey());
            }
        }
        return out;
    }

    /** Behaviours available for a role, default first (declaration order). */
    static List<RoleBehaviour> forRole(PlayerRole role) {
        return Arrays.stream(values()).filter(b -> b.role == role).toList();
    }
}
