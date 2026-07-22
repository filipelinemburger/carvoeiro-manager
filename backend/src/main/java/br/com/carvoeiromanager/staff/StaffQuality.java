package br.com.carvoeiromanager.staff;

/** How good a hired staff member is; the hiring/wage band around the 1-20 attributes. */
public enum StaffQuality {
    REGULAR, BOM, EXCELENTE;

    /** Tier implied by an attribute average: >=15 EXCELENTE, >=12 BOM, else REGULAR. */
    public static StaffQuality forAverage(double average) {
        if (average >= 15.0) {
            return EXCELENTE;
        }
        return average >= 12.0 ? BOM : REGULAR;
    }
}
