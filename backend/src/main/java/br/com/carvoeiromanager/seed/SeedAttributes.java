package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;

/**
 * The 22 hand-authored ("real", internet-researched) attributes that a seed player may carry, on
 * top of the six anchor values ({@code pace, finishing, passing, tackling, goalkeeping, stamina})
 * already on {@link SeedPlayer}. When present, career creation builds the player's full 28-attribute
 * profile from these authored values instead of the derived {@code AttributeDeriver} fallback.
 *
 * <p>Field names match {@link Attributes}' field names for readability; JSON binds by name, so a
 * player without an {@code attributes} block deserializes this as {@code null} and keeps the
 * derived profile.
 */
public record SeedAttributes(
        // Técnico (6)
        int drible,
        int cruzamento,
        int cabeceio,
        int marcacao,
        int bolaParada,
        int chuteLonge,
        // Mental (8)
        int visao,
        int posicionamento,
        int decisao,
        int compostura,
        int concentracao,
        int determinacao,
        int agressividade,
        int lideranca,
        // Físico (5)
        int aceleracao,
        int forca,
        int resistencia,
        int equilibrio,
        int agilidade,
        // Goleiro (3)
        int posicionamentoGk,
        int saidaDeGol,
        int jogoComOsPes
) {

    /**
     * Assembles the full 28-attribute value object: the six anchors carry their mapped attributes
     * ({@code pace→VELOCIDADE}, {@code finishing→FINALIZACAO}, {@code passing→PASSE},
     * {@code tackling→DESARME}, {@code goalkeeping→REFLEXOS}, {@code stamina→FOLEGO}) and the 22
     * authored values fill the rest. This is the single place the 28-key mapping lives.
     */
    public Attributes toAttributes(int pace, int finishing, int passing,
                                   int tackling, int goalkeeping, int stamina) {
        return Attributes.builder()
                // anchors
                .set(Attribute.VELOCIDADE, pace)
                .set(Attribute.FINALIZACAO, finishing)
                .set(Attribute.PASSE, passing)
                .set(Attribute.DESARME, tackling)
                .set(Attribute.REFLEXOS, goalkeeping)
                .set(Attribute.FOLEGO, stamina)
                // Técnico
                .set(Attribute.DRIBLE, drible)
                .set(Attribute.CRUZAMENTO, cruzamento)
                .set(Attribute.CABECEIO, cabeceio)
                .set(Attribute.MARCACAO, marcacao)
                .set(Attribute.BOLA_PARADA, bolaParada)
                .set(Attribute.CHUTE_LONGE, chuteLonge)
                // Mental
                .set(Attribute.VISAO, visao)
                .set(Attribute.POSICIONAMENTO, posicionamento)
                .set(Attribute.DECISAO, decisao)
                .set(Attribute.COMPOSTURA, compostura)
                .set(Attribute.CONCENTRACAO, concentracao)
                .set(Attribute.DETERMINACAO, determinacao)
                .set(Attribute.AGRESSIVIDADE, agressividade)
                .set(Attribute.LIDERANCA, lideranca)
                // Físico
                .set(Attribute.ACELERACAO, aceleracao)
                .set(Attribute.FORCA, forca)
                .set(Attribute.RESISTENCIA, resistencia)
                .set(Attribute.EQUILIBRIO, equilibrio)
                .set(Attribute.AGILIDADE, agilidade)
                // Goleiro
                .set(Attribute.POSICIONAMENTO_GK, posicionamentoGk)
                .set(Attribute.SAIDA_DE_GOL, saidaDeGol)
                .set(Attribute.JOGO_COM_OS_PES, jogoComOsPes)
                .build();
    }
}
