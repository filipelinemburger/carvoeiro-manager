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
}
