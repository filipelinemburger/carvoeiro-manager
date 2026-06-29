package br.com.elifootcarvoeiro.transfer;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/** Date-based transfer-window gating: a pre-season range and a mid-season range. */
@Component
public class TransferWindow {

    static final int PRE_SEASON_DAYS = 42;
    static final int MID_WINDOW_START_DAYS = 140;
    static final int MID_WINDOW_END_DAYS = 168;

    /**
     * Date-based window: a season runs within one calendar year (Jan 15 -> ~Oct), so the
     * season start is derived from the date's year. Open in the pre-season range
     * [start, start+42d) or the mid-season range [start+140d, start+168d).
     */
    public boolean isOpen(LocalDate currentDate) {
        LocalDate seasonStart = LocalDate.of(currentDate.getYear(), 1, 15);
        if (!currentDate.isBefore(seasonStart) && currentDate.isBefore(seasonStart.plusDays(PRE_SEASON_DAYS))) {
            return true;
        }
        LocalDate midStart = seasonStart.plusDays(MID_WINDOW_START_DAYS);
        LocalDate midEnd = seasonStart.plusDays(MID_WINDOW_END_DAYS);
        return !currentDate.isBefore(midStart) && currentDate.isBefore(midEnd);
    }
}
