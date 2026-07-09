package br.com.carvoeiromanager.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResponseTest {

    private List<Integer> tenItems() {
        return List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void firstPageReturnsTheRequestedSlice() {
        PageResponse<Integer> page = PageResponse.of(tenItems(), 1, 3);
        assertThat(page.content()).containsExactly(1, 2, 3);
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(3);
        assertThat(page.totalElements()).isEqualTo(10);
        assertThat(page.totalPages()).isEqualTo(4);
    }

    @Test
    void middlePageReturnsTheCorrectSlice() {
        PageResponse<Integer> page = PageResponse.of(tenItems(), 2, 3);
        assertThat(page.content()).containsExactly(4, 5, 6);
    }

    @Test
    void lastPartialPageReturnsOnlyTheRemainingItems() {
        PageResponse<Integer> page = PageResponse.of(tenItems(), 4, 3);
        assertThat(page.content()).containsExactly(10);
    }

    @Test
    void pageZeroOrNegativeIsClampedToOne() {
        assertThat(PageResponse.of(tenItems(), 0, 3).content()).containsExactly(1, 2, 3);
        assertThat(PageResponse.of(tenItems(), -5, 3).content()).containsExactly(1, 2, 3);
    }

    @Test
    void sizeBelowOneIsClampedToOne() {
        PageResponse<Integer> page = PageResponse.of(tenItems(), 1, 0);
        assertThat(page.size()).isEqualTo(1);
        assertThat(page.content()).containsExactly(1);
    }

    @Test
    void sizeAboveOneHundredIsClampedToOneHundred() {
        PageResponse<Integer> page = PageResponse.of(tenItems(), 1, 500);
        assertThat(page.size()).isEqualTo(100);
        assertThat(page.content()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
    }

    @Test
    void pageBeyondTheDataReturnsEmptyContentButKeepsTotals() {
        PageResponse<Integer> page = PageResponse.of(tenItems(), 10, 3);
        assertThat(page.content()).isEmpty();
        assertThat(page.page()).isEqualTo(10);
        assertThat(page.totalElements()).isEqualTo(10);
        assertThat(page.totalPages()).isEqualTo(4);
    }

    @Test
    void emptyInputYieldsAnEmptyPageWithZeroTotals() {
        PageResponse<Integer> page = PageResponse.of(List.of(), 1, 20);
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
        assertThat(page.totalPages()).isZero();
    }
}
