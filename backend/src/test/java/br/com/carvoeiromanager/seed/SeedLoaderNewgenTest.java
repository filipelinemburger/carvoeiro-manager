package br.com.carvoeiromanager.seed;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeedLoaderNewgenTest {

    private final SeedLoader loader = new SeedLoader();

    @Test
    void loadsNonEmptyNewgenNamePools() {
        // When loading the newgen name pools
        NewgenNames names = loader.loadNewgenNames();

        // Then both pools are present and non-trivial
        assertThat(names.firstNames()).hasSizeGreaterThan(10);
        assertThat(names.lastNames()).hasSizeGreaterThan(10);
    }
}
