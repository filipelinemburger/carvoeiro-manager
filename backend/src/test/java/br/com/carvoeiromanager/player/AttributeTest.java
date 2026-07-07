package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class AttributeTest {

    @Test
    void hasTwentyEightAttributes() {
        assertThat(Attribute.values()).hasSize(28);
    }

    @Test
    void groupsHaveTheExpectedCounts() {
        assertThat(Attribute.inGroup(Attribute.Group.TECNICO)).hasSize(9);
        assertThat(Attribute.inGroup(Attribute.Group.MENTAL)).hasSize(8);
        assertThat(Attribute.inGroup(Attribute.Group.FISICO)).hasSize(7);
        assertThat(Attribute.inGroup(Attribute.Group.GOLEIRO)).hasSize(4);
    }

    @Test
    void everyAttributeHasAGroupAndNonBlankLabel() {
        for (Attribute a : Attribute.values()) {
            assertThat(a.group()).isNotNull();
            assertThat(a.label()).isNotBlank();
        }
    }

    @Test
    void everyAttributeBelongsToExactlyOneGroup() {
        int grouped = Arrays.stream(Attribute.Group.values())
                .mapToInt(g -> Attribute.inGroup(g).size())
                .sum();
        assertThat(grouped).isEqualTo(Attribute.values().length);
    }
}
