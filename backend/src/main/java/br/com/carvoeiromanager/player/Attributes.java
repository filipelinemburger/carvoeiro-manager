package br.com.carvoeiromanager.player;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.EnumMap;
import java.util.Map;

/**
 * A player's full 28-attribute profile, embedded in {@link Player}. Six values reuse the
 * legacy columns as anchors (pace→VELOCIDADE, finishing→FINALIZACAO, passing→PASSE,
 * tackling→DESARME, goalkeeping→REFLEXOS, stamina→FOLEGO); the other 22 are new columns
 * added in V20. Values are clamped to [0, 99]. Read by {@link Attribute} key via
 * {@link #get(Attribute)} so callers never depend on field/column names.
 */
@Embeddable
public class Attributes {

    // --- Técnico (6 new, 3 legacy) ---
    @Column(name = "finishing", nullable = false) private int finalizacao;
    @Column(name = "passing", nullable = false) private int passe;
    @Column(name = "tackling", nullable = false) private int desarme;
    @Column(name = "drible", nullable = false) private int drible;
    @Column(name = "cruzamento", nullable = false) private int cruzamento;
    @Column(name = "cabeceio", nullable = false) private int cabeceio;
    @Column(name = "marcacao", nullable = false) private int marcacao;
    @Column(name = "bola_parada", nullable = false) private int bolaParada;
    @Column(name = "chute_longe", nullable = false) private int chuteLonge;

    // --- Mental (8 new) ---
    @Column(name = "visao", nullable = false) private int visao;
    @Column(name = "posicionamento", nullable = false) private int posicionamento;
    @Column(name = "decisao", nullable = false) private int decisao;
    @Column(name = "compostura", nullable = false) private int compostura;
    @Column(name = "concentracao", nullable = false) private int concentracao;
    @Column(name = "determinacao", nullable = false) private int determinacao;
    @Column(name = "agressividade", nullable = false) private int agressividade;
    @Column(name = "lideranca", nullable = false) private int lideranca;

    // --- Físico (5 new, 2 legacy) ---
    @Column(name = "pace", nullable = false) private int velocidade;
    @Column(name = "aceleracao", nullable = false) private int aceleracao;
    @Column(name = "forca", nullable = false) private int forca;
    @Column(name = "stamina", nullable = false) private int folego;
    @Column(name = "resistencia", nullable = false) private int resistencia;
    @Column(name = "equilibrio", nullable = false) private int equilibrio;
    @Column(name = "agilidade", nullable = false) private int agilidade;

    // --- Goleiro (3 new, 1 legacy) ---
    @Column(name = "goalkeeping", nullable = false) private int reflexos;
    @Column(name = "posicionamento_gk", nullable = false) private int posicionamentoGk;
    @Column(name = "saida_de_gol", nullable = false) private int saidaDeGol;
    @Column(name = "jogo_com_os_pes", nullable = false) private int jogoComOsPes;

    protected Attributes() {
        // JPA
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(99, v));
    }

    /** Reads an attribute's value. */
    public int get(Attribute a) {
        return switch (a) {
            case FINALIZACAO -> finalizacao;
            case PASSE -> passe;
            case DESARME -> desarme;
            case DRIBLE -> drible;
            case CRUZAMENTO -> cruzamento;
            case CABECEIO -> cabeceio;
            case MARCACAO -> marcacao;
            case BOLA_PARADA -> bolaParada;
            case CHUTE_LONGE -> chuteLonge;
            case VISAO -> visao;
            case POSICIONAMENTO -> posicionamento;
            case DECISAO -> decisao;
            case COMPOSTURA -> compostura;
            case CONCENTRACAO -> concentracao;
            case DETERMINACAO -> determinacao;
            case AGRESSIVIDADE -> agressividade;
            case LIDERANCA -> lideranca;
            case VELOCIDADE -> velocidade;
            case ACELERACAO -> aceleracao;
            case FORCA -> forca;
            case FOLEGO -> folego;
            case RESISTENCIA -> resistencia;
            case EQUILIBRIO -> equilibrio;
            case AGILIDADE -> agilidade;
            case REFLEXOS -> reflexos;
            case POSICIONAMENTO_GK -> posicionamentoGk;
            case SAIDA_DE_GOL -> saidaDeGol;
            case JOGO_COM_OS_PES -> jogoComOsPes;
        };
    }

    private void assign(Attribute a, int raw) {
        int v = clamp(raw);
        switch (a) {
            case FINALIZACAO -> finalizacao = v;
            case PASSE -> passe = v;
            case DESARME -> desarme = v;
            case DRIBLE -> drible = v;
            case CRUZAMENTO -> cruzamento = v;
            case CABECEIO -> cabeceio = v;
            case MARCACAO -> marcacao = v;
            case BOLA_PARADA -> bolaParada = v;
            case CHUTE_LONGE -> chuteLonge = v;
            case VISAO -> visao = v;
            case POSICIONAMENTO -> posicionamento = v;
            case DECISAO -> decisao = v;
            case COMPOSTURA -> compostura = v;
            case CONCENTRACAO -> concentracao = v;
            case DETERMINACAO -> determinacao = v;
            case AGRESSIVIDADE -> agressividade = v;
            case LIDERANCA -> lideranca = v;
            case VELOCIDADE -> velocidade = v;
            case ACELERACAO -> aceleracao = v;
            case FORCA -> forca = v;
            case FOLEGO -> folego = v;
            case RESISTENCIA -> resistencia = v;
            case EQUILIBRIO -> equilibrio = v;
            case AGILIDADE -> agilidade = v;
            case REFLEXOS -> reflexos = v;
            case POSICIONAMENTO_GK -> posicionamentoGk = v;
            case SAIDA_DE_GOL -> saidaDeGol = v;
            case JOGO_COM_OS_PES -> jogoComOsPes = v;
        }
    }

    /** Builds from a map; any attribute not present defaults to 0. */
    public static Attributes of(Map<Attribute, Integer> values) {
        Attributes attrs = new Attributes();
        for (Attribute a : Attribute.values()) {
            Integer v = values.get(a);
            attrs.assign(a, v == null ? 0 : v);
        }
        return attrs;
    }

    /** A copy with a single attribute changed (used by end-of-season evolution). */
    public Attributes with(Attribute a, int value) {
        Attributes copy = Attributes.of(asMap());
        copy.assign(a, value);
        return copy;
    }

    /** All 28 attributes as a map, in canonical order. */
    public Map<Attribute, Integer> asMap() {
        EnumMap<Attribute, Integer> map = new EnumMap<>(Attribute.class);
        for (Attribute a : Attribute.values()) {
            map.put(a, get(a));
        }
        return map;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder assembling attributes by name (never positionally). */
    public static final class Builder {
        private final EnumMap<Attribute, Integer> values = new EnumMap<>(Attribute.class);

        public Builder set(Attribute a, int value) {
            values.put(a, value);
            return this;
        }

        public Attributes build() {
            return Attributes.of(values);
        }
    }
}
