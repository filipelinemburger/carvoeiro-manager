package br.com.carvoeiromanager.staff;

import java.math.BigDecimal;

/** How good a hired staff member is; the hiring/wage band around the 1-20 attributes. */
public enum StaffQuality {
    REGULAR(new BigDecimal("20000.00")),
    BOM(new BigDecimal("45000.00")),
    EXCELENTE(new BigDecimal("80000.00"));

    private final BigDecimal monthlyWage;

    StaffQuality(BigDecimal monthlyWage) {
        this.monthlyWage = monthlyWage;
    }

    /** The monthly wage a staff member of this tier costs, for hires and for seeded staff alike. */
    public BigDecimal monthlyWage() {
        return monthlyWage;
    }

    /** Tier implied by an attribute average: >=15 EXCELENTE, >=12 BOM, else REGULAR. */
    public static StaffQuality forAverage(double average) {
        if (average >= 15.0) {
            return EXCELENTE;
        }
        return average >= 12.0 ? BOM : REGULAR;
    }
}
