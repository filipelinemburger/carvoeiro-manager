package br.com.carvoeiromanager.player;

public enum PlayerRole {
    GOALKEEPER(Position.GK,       new double[]{0,    0,   0,   0,    0},   new double[]{0, 0, 0, 0,    1.0}),
    STOPPER(Position.DEF,         new double[]{0.1,  0.1, 0,   0,    0},   new double[]{0, 0, 0, 1.0,  0}),
    FULL_BACK(Position.DEF,       new double[]{0.3,  0,   0.2, 0,    0},   new double[]{0, 0, 0, 0.8,  0}),
    BALL_PLAYING_DEF(Position.DEF,new double[]{0.1,  0,   0.4, 0,    0},   new double[]{0, 0, 0, 0.85, 0}),
    HOLDING_MID(Position.MID,     new double[]{0,    0,   0.3, 0,    0},   new double[]{0, 0, 0, 0.8,  0}),
    BOX_TO_BOX(Position.MID,      new double[]{0.2,  0.3, 0.3, 0,    0},   new double[]{0, 0, 0, 0.5,  0}),
    PLAYMAKER(Position.MID,       new double[]{0,    0.2, 0.6, 0,    0},   new double[]{0, 0, 0, 0.2,  0}),
    POACHER(Position.ATT,         new double[]{0.2,  0.8, 0,   0,    0},   new double[]{0, 0, 0, 0,    0}),
    TARGET_MAN(Position.ATT,      new double[]{0,    0.6, 0.2, 0,    0},   new double[]{0, 0, 0, 0.1,  0}),
    WINGER(Position.ATT,          new double[]{0.6,  0.2, 0.2, 0,    0},   new double[]{0, 0, 0, 0.05, 0});

    private final Position band;
    private final double[] attackWeights;
    private final double[] defenseWeights;

    PlayerRole(Position band, double[] attackWeights, double[] defenseWeights) {
        this.band = band;
        this.attackWeights = attackWeights;
        this.defenseWeights = defenseWeights;
    }

    public boolean isValidFor(Position position) { return band == position; }

    public double attackContribution(Player p) { return weighted(attackWeights, p); }

    public double defenseContribution(Player p) { return weighted(defenseWeights, p); }

    private double weighted(double[] w, Player p) {
        return w[0] * p.getPace()
                + w[1] * p.getFinishing()
                + w[2] * p.getPassing()
                + w[3] * p.getTackling()
                + w[4] * p.getGoalkeeping();
    }

    public static PlayerRole defaultFor(Position position) {
        return switch (position) {
            case GK -> GOALKEEPER;
            case DEF -> FULL_BACK;
            case MID -> BOX_TO_BOX;
            case ATT -> POACHER;
        };
    }
}
