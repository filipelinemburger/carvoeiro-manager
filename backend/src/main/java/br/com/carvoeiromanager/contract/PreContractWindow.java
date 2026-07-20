package br.com.carvoeiromanager.contract;

import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Date gate for pre-contract negotiation: open from July 15 (exactly 6 months before the
 * mid-January rollover where final-year contracts lapse) to the end of the season year.
 * Deliberately independent of TransferWindow — pre-contracts work outside transfer windows.
 */
@Component
public class PreContractWindow {

    public boolean isOpen(LocalDate currentDate) {
        return !currentDate.isBefore(LocalDate.of(currentDate.getYear(), 7, 15));
    }
}
