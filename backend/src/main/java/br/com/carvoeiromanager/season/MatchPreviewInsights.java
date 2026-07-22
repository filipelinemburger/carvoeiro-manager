package br.com.carvoeiromanager.season;

import java.util.List;
import java.util.Random;

/**
 * Composes the Auxiliar Técnico's 3 tactical-advice sentences from the opponent's tactical
 * identity, the squad-strength gap and the venue. Pure and deterministic per seed (the
 * fixture id), in a professional assistant's voice — intentionally NOT the Mastella
 * commentary tone. Always exactly 3 lines: tactical counter, marking/space, strength/venue.
 */
final class MatchPreviewInsights {

    private MatchPreviewInsights() {
    }

    /**
     * @param overallDiff opponent average overall minus managed average overall
     *                    (positive = they are stronger)
     * @param home        whether the managed club plays at home
     */
    static List<String> compose(long seed, String mentality, String pressing, String marking,
                                int overallDiff, boolean home) {
        Random rng = new Random(seed);
        return List.of(
                pick(rng, tacticalLine(mentality, pressing)),
                pick(rng, markingLine(marking)),
                pick(rng, strengthLine(overallDiff, home)));
    }

    private static String pick(Random rng, String[] variants) {
        return variants[rng.nextInt(variants.length)];
    }

    private static String[] tacticalLine(String mentality, String pressing) {
        if ("HIGH".equals(pressing)) {
            return new String[] {
                    "Eles pressionam a saída de bola — bolas longas nas costas da linha de defesa podem render boas chances.",
                    "A marcação alta deles abre espaço atrás da zaga; velocidade no ataque será decisiva.",
            };
        }
        if ("LOW".equals(pressing)) {
            return new String[] {
                    "Eles recuam e fecham os espaços — precisaremos de paciência na troca de passes para desmontar o bloco baixo.",
                    "Com o bloco baixo deles, chutes de média distância e jogadas de bola parada ganham importância.",
            };
        }
        if ("ATTACKING".equals(mentality)) {
            return new String[] {
                    "Time que se lança ao ataque e deixa espaços — contra-ataques rápidos podem castigá-los.",
                    "Eles arriscam muito à frente; transições rápidas serão nossa melhor arma.",
            };
        }
        if ("DEFENSIVE".equals(mentality)) {
            return new String[] {
                    "Adversário fechado e reativo — precisaremos circular a bola e evitar contra-ataques bobos.",
                    "Eles apostam no erro do rival; atenção total na saída de bola.",
            };
        }
        return new String[] {
                "Equipe equilibrada, sem excessos — o jogo deve se decidir nos detalhes e na bola parada.",
                "Não dão espaços de graça; intensidade desde o primeiro minuto fará diferença.",
        };
    }

    private static String[] markingLine(String marking) {
        if ("MAN_TIGHT".equals(marking)) {
            return new String[] {
                    "A marcação individual deles pode ser explorada com movimentação e trocas de posição.",
                    "Contra marcação homem a homem, tabelas curtas e infiltrações quebram linhas.",
            };
        }
        return new String[] {
                "Na marcação por zona deles, inversões rápidas de jogo criam superioridade nos lados.",
                "Ataques pelos corredores forçam a zona deles a se deslocar e abrir espaços.",
        };
    }

    private static String[] strengthLine(int overallDiff, boolean home) {
        if (overallDiff >= 3) {
            return new String[] {
                    "No papel eles são favoritos — organização defensiva e eficiência nas poucas chances serão fundamentais.",
                    "Elenco superior ao nosso; um jogo disciplinado pode surpreendê-los.",
            };
        }
        if (overallDiff <= -3) {
            return new String[] {
                    "Somos favoritos, mas não podemos subestimá-los — vamos propor o jogo desde o início.",
                    "Temos elenco superior; pressão alta desde cedo pode resolver o jogo.",
            };
        }
        if (home) {
            return new String[] {
                    "Jogando em casa, nossa torcida deve pesar — vamos impor o ritmo desde o apito inicial.",
                    "Equilíbrio no papel; o mando de campo é a nossa vantagem — usar a pressão da torcida.",
            };
        }
        return new String[] {
                "Fora de casa, equilíbrio emocional e compactação nos primeiros minutos serão essenciais.",
                "Jogo parelho fora de casa — segurar o ímpeto inicial deles e crescer no jogo.",
        };
    }
}
