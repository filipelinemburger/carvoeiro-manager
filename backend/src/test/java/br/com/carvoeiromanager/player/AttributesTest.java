package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AttributesTest {

    private static Attributes full(int value) {
        Attributes.Builder b = Attributes.builder();
        for (Attribute a : Attribute.values()) {
            b.set(a, value);
        }
        return b.build();
    }

    @Test
    void builderRoundTripsEveryAttribute() {
        Map<Attribute, Integer> expected = new EnumMap<>(Attribute.class);
        int v = 10;
        Attributes.Builder b = Attributes.builder();
        for (Attribute a : Attribute.values()) {
            b.set(a, v);
            expected.put(a, v);
            v = (v % 90) + 5; // vary values so the mapping can't be a constant
        }
        Attributes attrs = b.build();
        for (Attribute a : Attribute.values()) {
            assertThat(attrs.get(a)).as(a.name()).isEqualTo(expected.get(a));
        }
    }

    @Test
    void asMapHasAllTwentyEight() {
        assertThat(full(40).asMap()).hasSize(28);
    }

    @Test
    void withChangesOnlyTheGivenAttribute() {
        Attributes base = full(50);
        Attributes changed = base.with(Attribute.FINALIZACAO, 88);
        assertThat(changed.get(Attribute.FINALIZACAO)).isEqualTo(88);
        assertThat(changed.get(Attribute.PASSE)).isEqualTo(50);
        // original is untouched (value object copy semantics)
        assertThat(base.get(Attribute.FINALIZACAO)).isEqualTo(50);
    }

    @Test
    void valuesAreClampedToZeroToNinetyNine() {
        Attributes base = full(50);
        assertThat(base.with(Attribute.VELOCIDADE, 150).get(Attribute.VELOCIDADE)).isEqualTo(99);
        assertThat(base.with(Attribute.VELOCIDADE, -20).get(Attribute.VELOCIDADE)).isEqualTo(0);
    }

    @Test
    void missingAttributesDefaultToZero() {
        Attributes attrs = Attributes.builder().set(Attribute.FINALIZACAO, 70).build();
        assertThat(attrs.get(Attribute.FINALIZACAO)).isEqualTo(70);
        assertThat(attrs.get(Attribute.VISAO)).isEqualTo(0);
    }
}
