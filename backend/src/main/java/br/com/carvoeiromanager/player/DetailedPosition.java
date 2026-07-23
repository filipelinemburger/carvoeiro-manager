package br.com.carvoeiromanager.player;

import java.util.Random;

/**
 * A fine-grained playing position that refines the coarse {@link Position}. Every player carries one:
 * seeded players and free agents get one at career creation (or via the V39 backfill for legacy saves),
 * and every generated player (newgen / youth prospect) gets one at construction.
 *
 * <p>{@link #coarse()} always maps back to the owning {@link Position}, so a detailed position never
 * contradicts a player's coarse position.
 */
public enum DetailedPosition {

    GOLEIRO(Position.GK, "Goleiro", "GOL"),
    ZAGUEIRO(Position.DEF, "Zagueiro", "ZAG"),
    LATERAL_ESQUERDO(Position.DEF, "Lateral esquerdo", "LE"),
    LATERAL_DIREITO(Position.DEF, "Lateral direito", "LD"),
    VOLANTE(Position.MID, "Volante", "VOL"),
    MEIA_ARMADOR(Position.MID, "Meia armador", "MEI"),
    PONTA(Position.ATT, "Ponta", "PON"),
    FALSO_9(Position.ATT, "Falso 9", "F9"),
    ATACANTE(Position.ATT, "Atacante", "ATA");

    private final Position coarse;
    private final String label;
    private final String shortLabel;

    DetailedPosition(Position coarse, String label, String shortLabel) {
        this.coarse = coarse;
        this.label = label;
        this.shortLabel = shortLabel;
    }

    public Position coarse() {
        return coarse;
    }

    public String label() {
        return label;
    }

    public String shortLabel() {
        return shortLabel;
    }

    /**
     * Picks a coarse-consistent detailed position for a player, adding realistic variety within a
     * coarse position. Draws from {@code rng}; callers that must not disturb a shared generation
     * stream pass a per-player {@link Random} seeded off the player's id.
     *
     * <p>The result always satisfies {@code defaultFor(position, passe, rng).coarse() == position}.
     *
     * @param position the coarse position the detailed value must belong to
     * @param passe    the player's PASSE attribute (only the FALSO_9 rule consults it)
     * @param rng      the source of variety
     */
    public static DetailedPosition defaultFor(Position position, int passe, Random rng) {
        return switch (position) {
            case GK -> GOLEIRO;
            case DEF -> {
                int r = rng.nextInt(5);
                if (r < 3) {
                    yield ZAGUEIRO;
                }
                yield r == 3 ? LATERAL_ESQUERDO : LATERAL_DIREITO;
            }
            case MID -> rng.nextBoolean() ? VOLANTE : MEIA_ARMADOR;
            case ATT -> {
                if (passe >= 60 && rng.nextInt(10) == 0) {
                    yield FALSO_9;
                }
                // Draw the ponta/atacante split unconditionally so the distribution is stable
                // regardless of whether the FALSO_9 branch fired.
                yield rng.nextInt(10) < 3 ? PONTA : ATACANTE;
            }
        };
    }
}
