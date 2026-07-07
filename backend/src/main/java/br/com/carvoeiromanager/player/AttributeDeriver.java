package br.com.carvoeiromanager.player;

import java.util.Random;

/**
 * Derives a full 28-attribute profile from a player's six anchor attributes + position, with
 * small deterministic jitter. Pure and reproducible (same inputs → same output) and it never
 * touches a match/generation RNG: the six anchors are carried through unchanged and the new
 * attributes are inferred from correlated anchors, so a generated player's anchors + overall
 * stay byte-identical to the legacy six-attribute model. Shared by the newgen/free-agent
 * generators and the seed regeneration tool.
 */
public final class AttributeDeriver {

    private AttributeDeriver() {
    }

    public static Attributes derive(Position position, int pace, int finishing, int passing,
                                    int tackling, int goalkeeping, int stamina, int overall) {
        Random j = new Random(seed(position, pace, finishing, passing, tackling, goalkeeping, stamina, overall));
        int skill = overall;

        Attributes.Builder b = Attributes.builder()
                .set(Attribute.VELOCIDADE, pace)
                .set(Attribute.FINALIZACAO, finishing)
                .set(Attribute.PASSE, passing)
                .set(Attribute.DESARME, tackling)
                .set(Attribute.REFLEXOS, goalkeeping)
                .set(Attribute.FOLEGO, stamina)
                // Técnico
                .set(Attribute.DRIBLE, jit((pace + finishing) / 2, j))
                .set(Attribute.CRUZAMENTO, jit((passing + pace) / 2, j))
                .set(Attribute.CABECEIO, jit((tackling + stamina) / 2, j))
                .set(Attribute.MARCACAO, jit(tackling, j))
                .set(Attribute.BOLA_PARADA, jit((finishing + passing) / 2, j))
                .set(Attribute.CHUTE_LONGE, jit((finishing + passing) / 2, j))
                // Mental
                .set(Attribute.VISAO, jit(passing, j))
                .set(Attribute.POSICIONAMENTO, jit(skill, j))
                .set(Attribute.DECISAO, jit(skill, j))
                .set(Attribute.COMPOSTURA, jit(position == Position.ATT ? finishing : skill, j))
                .set(Attribute.CONCENTRACAO, jit(skill, j))
                .set(Attribute.DETERMINACAO, jit(skill, j))
                .set(Attribute.AGRESSIVIDADE, jit(tackling, j))
                .set(Attribute.LIDERANCA, jit(Math.max(1, skill - 10), j))
                // Físico
                .set(Attribute.ACELERACAO, jit(pace, j))
                .set(Attribute.FORCA, jit((tackling + stamina) / 2, j))
                .set(Attribute.RESISTENCIA, jit(stamina, j))
                .set(Attribute.EQUILIBRIO, jit((pace + stamina) / 2, j))
                .set(Attribute.AGILIDADE, jit(pace, j));

        // Goalkeeping attributes matter only for keepers; outfielders sit low (~1-10).
        if (position == Position.GK) {
            b.set(Attribute.POSICIONAMENTO_GK, jit(goalkeeping, j))
                    .set(Attribute.SAIDA_DE_GOL, jit(Math.max(1, goalkeeping - 5), j))
                    .set(Attribute.JOGO_COM_OS_PES, jit(passing, j));
        } else {
            b.set(Attribute.POSICIONAMENTO_GK, 1 + j.nextInt(9))
                    .set(Attribute.SAIDA_DE_GOL, 1 + j.nextInt(9))
                    .set(Attribute.JOGO_COM_OS_PES, 1 + j.nextInt(9));
        }
        return b.build();
    }

    private static int jit(int base, Random j) {
        return base + j.nextInt(11) - 5; // ±5; Attributes clamps to [0, 99]
    }

    private static long seed(Position position, int pace, int finishing, int passing,
                             int tackling, int goalkeeping, int stamina, int overall) {
        long h = position.ordinal();
        for (int v : new int[]{pace, finishing, passing, tackling, goalkeeping, stamina, overall}) {
            h = h * 92821 + v;
        }
        return h;
    }
}
