package br.com.carvoeiromanager.player;

import java.util.List;
import java.util.stream.Stream;

/**
 * The full Championship-Manager-style player attribute set (each 0-99), grouped for
 * display and scoring. Declaration order is the canonical order used by the API and the
 * player-details board; each attribute belongs to exactly one {@link Group}.
 *
 * <p>Six attributes carry over the legacy single-value columns (VELOCIDADE&lt;-pace,
 * FINALIZACAO&lt;-finishing, PASSE&lt;-passing, DESARME&lt;-tackling, FOLEGO&lt;-stamina, and the
 * goalkeeping composite is split across the Goleiro group); the rest are new.
 */
public enum Attribute {
    // Técnico (9)
    FINALIZACAO(Group.TECNICO, "Finalização"),
    PASSE(Group.TECNICO, "Passe"),
    DESARME(Group.TECNICO, "Desarme"),
    DRIBLE(Group.TECNICO, "Drible"),
    CRUZAMENTO(Group.TECNICO, "Cruzamento"),
    CABECEIO(Group.TECNICO, "Cabeceio"),
    MARCACAO(Group.TECNICO, "Marcação"),
    BOLA_PARADA(Group.TECNICO, "Bola parada"),
    CHUTE_LONGE(Group.TECNICO, "Chute de longe"),

    // Mental (8)
    VISAO(Group.MENTAL, "Visão de jogo"),
    POSICIONAMENTO(Group.MENTAL, "Posicionamento"),
    DECISAO(Group.MENTAL, "Decisão"),
    COMPOSTURA(Group.MENTAL, "Compostura"),
    CONCENTRACAO(Group.MENTAL, "Concentração"),
    DETERMINACAO(Group.MENTAL, "Determinação"),
    AGRESSIVIDADE(Group.MENTAL, "Agressividade"),
    LIDERANCA(Group.MENTAL, "Liderança"),

    // Físico (7)
    VELOCIDADE(Group.FISICO, "Velocidade"),
    ACELERACAO(Group.FISICO, "Aceleração"),
    FORCA(Group.FISICO, "Força"),
    FOLEGO(Group.FISICO, "Fôlego"),
    RESISTENCIA(Group.FISICO, "Resistência"),
    EQUILIBRIO(Group.FISICO, "Equilíbrio"),
    AGILIDADE(Group.FISICO, "Agilidade"),

    // Goleiro (4)
    REFLEXOS(Group.GOLEIRO, "Reflexos"),
    POSICIONAMENTO_GK(Group.GOLEIRO, "Posicionamento (GL)"),
    SAIDA_DE_GOL(Group.GOLEIRO, "Saída de gol"),
    JOGO_COM_OS_PES(Group.GOLEIRO, "Jogo com os pés");

    /** Display grouping for the attribute board. */
    public enum Group {
        TECNICO("Técnico"),
        MENTAL("Mental"),
        FISICO("Físico"),
        GOLEIRO("Goleiro");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Group group;
    private final String label;

    Attribute(Group group, String label) {
        this.group = group;
        this.label = label;
    }

    public Group group() {
        return group;
    }

    public String label() {
        return label;
    }

    /** Attributes in the given group, in canonical (declaration) order. */
    public static List<Attribute> inGroup(Group group) {
        return Stream.of(values()).filter(a -> a.group == group).toList();
    }
}
