package br.com.carvoeiromanager.club;

import org.junit.jupiter.api.Test;

import java.time.Month;

import static org.assertj.core.api.Assertions.assertThat;

class ClimateTableTest {

    @Test
    void everyZoneDefinesAllTwelveMonths() {
        for (ClimateZone zone : ClimateZone.values()) {
            for (Month month : Month.values()) {
                MonthProfile profile = ClimateTable.profileFor(zone, month);
                assertThat(profile).as("%s/%s", zone, month).isNotNull();
                assertThat(profile.month()).isEqualTo(month.getValue());
                assertThat(profile.minC()).isLessThanOrEqualTo(profile.maxC());
                assertThat(profile.rainChance()).isBetween(0.0, 1.0);
                assertThat(profile.windChance()).isBetween(0.0, 1.0);
            }
        }
    }

    @Test
    void southernWinterIsColderThanNortheasternWinter() {
        MonthProfile sul = ClimateTable.profileFor(ClimateZone.SUL, Month.JULY);
        MonthProfile nordeste = ClimateTable.profileFor(ClimateZone.NORDESTE_LITORAL, Month.JULY);
        assertThat(sul.maxC()).isLessThan(nordeste.maxC());
        assertThat(sul.minC()).isLessThan(nordeste.minC());
    }

    @Test
    void northeasternCoastNeverGetsCold() {
        for (Month month : Month.values()) {
            assertThat(ClimateTable.profileFor(ClimateZone.NORDESTE_LITORAL, month).minC())
                    .as("%s", month)
                    .isGreaterThanOrEqualTo(18);
        }
    }

    @Test
    void lazyLoadingCachesSuccessfullyAcrossMultipleCalls() {
        MonthProfile first = ClimateTable.profileFor(ClimateZone.SUDESTE, Month.JANUARY);
        MonthProfile second = ClimateTable.profileFor(ClimateZone.SUDESTE, Month.JANUARY);
        assertThat(first).isEqualTo(second);
        assertThat(first.month()).isEqualTo(1);
    }

    @Test
    void norteIsAFullEquatorialProfileDistinctFromTheNortheastCoast() {
        // Manaus and São Luís have no honest zone without this; before NORTE they would have been
        // stretched onto NORDESTE_LITORAL, as Belém already was.
        for (Month month : Month.values()) {
            MonthProfile p = ClimateZone.NORTE.profileFor(month);
            assertThat(p.minC()).as("minC in %s", month).isBetween(20, 27);
            assertThat(p.maxC()).as("maxC in %s", month).isBetween(28, 36);
            assertThat(p.rainChance()).as("rain in %s", month).isBetween(0.15, 0.80);
            assertThat(p.windChance()).as("wind in %s", month).isBetween(0.01, 0.10);
        }
        // Equatorial: a pronounced wet season Dec-May, unlike the northeastern coast's autumn peak.
        assertThat(ClimateZone.NORTE.profileFor(Month.MARCH).rainChance())
                .isGreaterThan(ClimateZone.NORTE.profileFor(Month.SEPTEMBER).rainChance());
        assertThat(ClimateZone.NORTE.profileFor(Month.MARCH).rainChance())
                .isGreaterThan(ClimateZone.NORDESTE_LITORAL.profileFor(Month.MARCH).rainChance());
    }

    @Test
    void everyZoneHasTwelveMonths() {
        // Guards the whole enum, so a future zone cannot be added without its data.
        for (ClimateZone zone : ClimateZone.values()) {
            for (Month month : Month.values()) {
                assertThat(zone.profileFor(month)).as("%s/%s", zone, month).isNotNull();
            }
        }
    }
}
