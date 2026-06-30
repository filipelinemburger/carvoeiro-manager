package br.com.carvoeiromanager.transfer;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure, deterministic market value: overall^3 * 5 * age factor (scale 2). */
@Component
public class TransferValuation {

    private static final BigDecimal VALUE_PER_CUBE = new BigDecimal("5");

    public BigDecimal marketValue(int overall, int age) {
        BigDecimal base = BigDecimal.valueOf((long) overall * overall * overall).multiply(VALUE_PER_CUBE);
        return base.multiply(ageFactor(age)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ageFactor(int age) {
        if (age <= 21) return new BigDecimal("1.05");
        if (age <= 29) return new BigDecimal("1.00");
        if (age <= 32) return new BigDecimal("0.70");
        if (age <= 34) return new BigDecimal("0.45");
        return new BigDecimal("0.20");
    }
}
