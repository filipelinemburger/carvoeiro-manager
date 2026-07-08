package br.com.carvoeiromanager.club;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClubTest {

    @Test
    void tacticalIdentityFieldsDefaultToNullAndAreSettable() {
        Club club = Club.create(java.util.UUID.randomUUID(), "Test FC", "TST");

        assertThat(club.getDefaultFormation()).isNull();
        assertThat(club.getDefaultMentality()).isNull();
        assertThat(club.getDefaultPressing()).isNull();
        assertThat(club.getDefaultMarking()).isNull();

        club.setDefaultFormation("4-3-3");
        club.setDefaultMentality("ATTACKING");
        club.setDefaultPressing("HIGH");
        club.setDefaultMarking("MAN_TIGHT");

        assertThat(club.getDefaultFormation()).isEqualTo("4-3-3");
        assertThat(club.getDefaultMentality()).isEqualTo("ATTACKING");
        assertThat(club.getDefaultPressing()).isEqualTo("HIGH");
        assertThat(club.getDefaultMarking()).isEqualTo("MAN_TIGHT");
    }
}
