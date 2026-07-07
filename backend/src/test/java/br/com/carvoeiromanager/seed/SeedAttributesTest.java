package br.com.carvoeiromanager.seed;

import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Attributes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedAttributesTest {

    /** The six anchors land on their mapped attributes; the 22 authored values fill the rest. */
    @Test
    void toAttributesMapsAnchorsAndAuthoredValues() {
        SeedAttributes seed = new SeedAttributes(
                71, 72, 73, 74, 75, 76,          // Técnico: drible..chuteLonge
                61, 62, 63, 64, 65, 66, 67, 68,  // Mental: visao..lideranca
                51, 52, 53, 54, 55,              // Físico: aceleracao..agilidade
                11, 12, 13);                     // Goleiro: posicionamentoGk..jogoComOsPes

        Attributes attrs = seed.toAttributes(40, 41, 42, 43, 44, 45);

        // Anchors mapped onto their attributes.
        assertThat(attrs.get(Attribute.VELOCIDADE)).isEqualTo(40);
        assertThat(attrs.get(Attribute.FINALIZACAO)).isEqualTo(41);
        assertThat(attrs.get(Attribute.PASSE)).isEqualTo(42);
        assertThat(attrs.get(Attribute.DESARME)).isEqualTo(43);
        assertThat(attrs.get(Attribute.REFLEXOS)).isEqualTo(44);
        assertThat(attrs.get(Attribute.FOLEGO)).isEqualTo(45);

        // A sample of the 22 authored values across every group.
        assertThat(attrs.get(Attribute.DRIBLE)).isEqualTo(71);
        assertThat(attrs.get(Attribute.CHUTE_LONGE)).isEqualTo(76);
        assertThat(attrs.get(Attribute.VISAO)).isEqualTo(61);
        assertThat(attrs.get(Attribute.LIDERANCA)).isEqualTo(68);
        assertThat(attrs.get(Attribute.ACELERACAO)).isEqualTo(51);
        assertThat(attrs.get(Attribute.AGILIDADE)).isEqualTo(55);
        assertThat(attrs.get(Attribute.POSICIONAMENTO_GK)).isEqualTo(11);
        assertThat(attrs.get(Attribute.JOGO_COM_OS_PES)).isEqualTo(13);
    }
}
