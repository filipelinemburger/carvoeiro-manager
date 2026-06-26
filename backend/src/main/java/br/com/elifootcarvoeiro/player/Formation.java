package br.com.elifootcarvoeiro.player;

public enum Formation {
    F_4_4_2("4-4-2", 1.00, 1.00),
    F_4_3_3("4-3-3", 1.10, 0.94),
    F_3_5_2("3-5-2", 1.06, 0.96),
    F_5_3_2("5-3-2", 0.92, 1.10),
    F_4_5_1("4-5-1", 0.96, 1.06),
    F_4_2_3_1("4-2-3-1", 1.04, 1.00);

    private final String code;
    private final double attackMult;
    private final double defenseMult;

    Formation(String code, double attackMult, double defenseMult) {
        this.code = code;
        this.attackMult = attackMult;
        this.defenseMult = defenseMult;
    }

    public String code() { return code; }
    public double attackMult() { return attackMult; }
    public double defenseMult() { return defenseMult; }

    public static Formation fromCode(String code) {
        if (code != null) {
            for (Formation f : values()) {
                if (f.code.equals(code)) {
                    return f;
                }
            }
        }
        return F_4_4_2;
    }
}
