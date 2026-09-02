package br.com.carvoeiromanager.player;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Maps to the {@code players} table created in V2__game.sql.
 * ID is assigned by the caller via {@code UUID.randomUUID()}.
 * All FKs are stored as plain UUID columns (no JPA associations).
 */
@Entity
@Table(name = "players")
public class Player {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "save_game_id", nullable = false)
    private UUID saveGameId;

    @Column(name = "club_id")
    private UUID clubId;

    @Column(name = "name", nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "position", nullable = false, length = 3)
    private Position position;

    @Enumerated(EnumType.STRING)
    @Column(name = "detailed_position", length = 20)
    private DetailedPosition detailedPosition;

    /** CSV of 0–2 alternate DetailedPosition names (spec §1.2); null = no alternates. */
    @Column(name = "alt_positions", length = 40)
    private String altPositions;

    /** CSV of familiar role tokens, e.g. "WINGBACK++,FULLBACK+" (spec §2.3). */
    @Column(name = "familiar_roles", nullable = false, length = 120)
    private String familiarRoles = "";

    /**
     * Lazily parsed views of the two CSV columns above, invalidated by their mutators. Not
     * persisted, and never part of equality or the entity's identity — purely a decode cache for
     * the match engine's hot loops (see {@link #positions()} / {@link #familiarRoles()}).
     */
    @Transient
    private List<DetailedPosition> positionsCache;

    @Transient
    private Map<RoleArchetype, Integer> familiarRolesCache;

    @Column(name = "age", nullable = false)
    private int age;

    @Column(name = "nationality", nullable = false, length = 40)
    private String nationality;

    @Embedded
    private Attributes attributes = new Attributes();

    @Column(name = "overall", nullable = false)
    private int overall;

    @Column(name = "form", nullable = false)
    private int form;

    @Column(name = "morale", nullable = false)
    private int morale;

    @Column(name = "yellow_cards", nullable = false)
    private int yellowCards;

    @Column(name = "suspension_remaining", nullable = false)
    private int suspensionRemaining;

    @Column(name = "injury_matches", nullable = false)
    private int injuryMatches;

    @Column(name = "fitness", nullable = false)
    private int fitness = 100;

    @Column(name = "youth_prospect", nullable = false)
    private boolean youthProspect;

    @Column(name = "potential", nullable = false)
    private int potential;

    @Column(name = "last_match_date")
    private LocalDate lastMatchDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "injury_severity", nullable = false, length = 10)
    private InjurySeverity injurySeverity = InjurySeverity.NONE;

    @Column(name = "wage", nullable = false)
    private BigDecimal wage = BigDecimal.ZERO;

    @Column(name = "contract_years", nullable = false)
    private int contractYears;

    @Column(name = "transfer_listed", nullable = false)
    private boolean transferListed;

    @Enumerated(EnumType.STRING)
    @Column(name = "listed_reason", length = 16)
    private ListedReason listedReason;

    /**
     * The club that terminated this player's contract, or null. Blocks that club — and only
     * that club — from re-signing him as a free agent, until {@link #resetSeasonState()}
     * clears it at rollover.
     */
    @Column(name = "released_by_club_id")
    private UUID releasedByClubId;

    /**
     * The club that owns this player while he is out on loan, or null when he plays for his
     * owner. Non-null is the definition of "on loan": {@code clubId} always means the club he
     * currently plays for, which is what the rest of the game asks.
     */
    @Column(name = "owner_club_id")
    private UUID ownerClubId;

    /** Percentage of this player's wage the OWNER pays while he is on loan; null when he is not. */
    @Column(name = "loan_wage_share")
    private Integer loanWageShare;

    /** Whether the borrowing club may field him against his owner; null when he is not on loan. */
    @Column(name = "loan_may_face_owner")
    private Boolean loanMayFaceOwner;

    @Enumerated(EnumType.STRING)
    @Column(name = "training_focus", length = 20)
    private Attribute trainingFocus;

    @Enumerated(EnumType.STRING)
    @Column(name = "training_intensity", nullable = false, length = 10)
    private TrainingIntensity trainingIntensity = TrainingIntensity.NORMAL;

    /** Accumulated weekly training, in fortieths of an attribute point (a point fires at 40). */
    @Column(name = "training_progress", nullable = false)
    private int trainingProgress = 0;

    @Column(name = "season_appearances", nullable = false)
    private int seasonAppearances;

    protected Player() {
        // for JPA
    }

    private Player(UUID id, UUID saveGameId, UUID clubId, String name, Position position,
                   int age, String nationality, Attributes attributes, int overall) {
        this.id = id;
        this.saveGameId = saveGameId;
        this.clubId = clubId;
        this.name = name;
        this.position = position;
        this.age = age;
        this.nationality = nationality;
        this.attributes = attributes;
        this.overall = overall;
        // Every constructed player gets a coarse-consistent detailed position (and 0–2 adjacent
        // alternates). Derived from a per-player Random seeded off the (already-assigned) id, so it
        // consumes NOTHING from any shared generation RNG — seeded generation/rollover output stays
        // byte-identical. This is the single source of truth: it covers seed load, free agents,
        // newgens, prospects and tests, so no path can hit the NOT-NULL detailed_position column with
        // a null value.
        Random posRng = new Random(id.getMostSignificantBits() ^ 0xDE7A11L);
        this.detailedPosition = DetailedPosition.defaultFor(position, posRng);
        List<DetailedPosition> alts = DetailedPosition.alternatesFor(this.detailedPosition, posRng);
        this.altPositions = alts.isEmpty() ? null
                : alts.stream().map(Enum::name).collect(Collectors.joining(","));
        this.familiarRoles = String.join(",",
                RoleArchetype.defaultFamiliarFor(this.detailedPosition, attributes, posRng));
    }

    public static Player create(UUID saveGameId, UUID clubId, String name, Position position,
                                int age, String nationality, int pace, int finishing,
                                int passing, int tackling, int goalkeeping, int stamina, int overall) {
        Attributes attributes = Attributes.builder()
                .set(Attribute.VELOCIDADE, pace)
                .set(Attribute.FINALIZACAO, finishing)
                .set(Attribute.PASSE, passing)
                .set(Attribute.DESARME, tackling)
                .set(Attribute.REFLEXOS, goalkeeping)
                .set(Attribute.FOLEGO, stamina)
                .build();
        return new Player(UUID.randomUUID(), saveGameId, clubId, name, position, age, nationality,
                attributes, overall);
    }

    /** Creates a player from a full attribute profile (generators + seed loader). */
    public static Player create(UUID saveGameId, UUID clubId, String name, Position position,
                                int age, String nationality, Attributes attributes, int overall) {
        return new Player(UUID.randomUUID(), saveGameId, clubId, name, position, age, nationality,
                attributes, overall);
    }

    public UUID getId() { return id; }
    public UUID getSaveGameId() { return saveGameId; }
    public UUID getClubId() { return clubId; }
    public String getName() { return name; }
    public Position getPosition() { return position; }
    public DetailedPosition getDetailedPosition() { return detailedPosition; }

    public void setDetailedPosition(DetailedPosition detailedPosition) {
        this.detailedPosition = detailedPosition;
        this.positionsCache = null;
    }

    /** Alternate positions (may be empty), parsed from the CSV column. */
    public List<DetailedPosition> getAltPositions() {
        if (altPositions == null || altPositions.isBlank()) {
            return List.of();
        }
        return Arrays.stream(altPositions.split(",")).map(DetailedPosition::valueOf).toList();
    }

    /**
     * All playable positions, primary first. Cached because the match engine calls this inside
     * O(slots × pool) loops — SlotAssigner probes it once per (player, slot) pair for naturality
     * and again for tactical distance — and each uncached call re-split the CSV and re-parsed
     * every enum. The cache is dropped by every mutator below; Hibernate writes the columns
     * directly on load, before anything can read it, so a hydrated entity starts cold.
     */
    public List<DetailedPosition> positions() {
        if (positionsCache == null) {
            List<DetailedPosition> all = new ArrayList<>(1 + getAltPositions().size());
            all.add(detailedPosition);
            all.addAll(getAltPositions());
            positionsCache = List.copyOf(all);
        }
        return positionsCache;
    }

    /** Replaces primary + alternates (researched seed data or migration backfill). */
    public void setPositions(DetailedPosition primary, List<DetailedPosition> alternates) {
        this.detailedPosition = primary;
        this.altPositions = alternates == null || alternates.isEmpty() ? null
                : alternates.stream().map(Enum::name).collect(Collectors.joining(","));
        this.positionsCache = null;
    }

    /**
     * Parses the familiar-role CSV into archetype → familiarity level (2 = ++, 1 = +),
     * insertion-ordered. Cached for the same reason as {@link #positions()}: {@link
     * #familiarity(RoleArchetype)} is called per player per strength calculation and each call
     * used to rebuild the whole map just to read one entry.
     */
    public Map<RoleArchetype, Integer> familiarRoles() {
        if (familiarRolesCache == null) {
            Map<RoleArchetype, Integer> out = new LinkedHashMap<>();
            if (familiarRoles != null && !familiarRoles.isBlank()) {
                for (String token : familiarRoles.split(",")) {
                    int level = token.endsWith("++") ? 2 : 1;
                    out.put(RoleArchetype.valueOf(token.substring(0, token.length() - (level == 2 ? 2 : 1))), level);
                }
            }
            familiarRolesCache = Collections.unmodifiableMap(out);
        }
        return familiarRolesCache;
    }

    public int familiarity(RoleArchetype archetype) {
        return familiarRoles().getOrDefault(archetype, 0);
    }

    /** Replaces the familiar-role tokens (validated: parseable, level suffix required). */
    public void setFamiliarRoles(List<String> tokens) {
        for (String t : tokens) {
            if (!t.endsWith("+")) {
                throw new IllegalArgumentException("Familiar role token missing +/++ suffix: " + t);
            }
            RoleArchetype.valueOf(t.replaceAll("\\+", ""));   // throws on unknown archetype
        }
        this.familiarRoles = String.join(",", tokens);
        this.familiarRolesCache = null;
    }

    public int getAge() { return age; }
    public String getNationality() { return nationality; }

    public boolean isYouthProspect() { return youthProspect; }
    public int getPotential() { return potential; }

    public void markAsProspect(int potential) {
        this.youthProspect = true;
        this.potential = potential;
    }

    public void promoteFromYouth() {
        this.youthProspect = false;
    }
    // The six legacy getters delegate to the embedded attribute profile (anchors), so every
    // existing caller and scoring path is unchanged by the storage move.
    public int getPace() { return attributes.get(Attribute.VELOCIDADE); }
    public int getFinishing() { return attributes.get(Attribute.FINALIZACAO); }
    public int getPassing() { return attributes.get(Attribute.PASSE); }
    public int getTackling() { return attributes.get(Attribute.DESARME); }
    public int getGoalkeeping() { return attributes.get(Attribute.REFLEXOS); }
    public int getStamina() { return attributes.get(Attribute.FOLEGO); }
    public int getOverall() { return overall; }

    public Attributes attributes() { return attributes; }
    public int attribute(Attribute a) { return attributes.get(a); }

    /**
     * Replaces a single attribute (clamped to [0, 99] by {@link Attributes#with}), leaving every
     * other attribute — including {@link #overall} — untouched. Used by {@link TrainingService}
     * to train the non-anchor focuses, whose overall is anchor-based by design and never moves.
     */
    public void setAttribute(Attribute a, int value) {
        this.attributes = this.attributes.with(a, value);
    }

    public void setClubId(UUID clubId) { this.clubId = clubId; }
    public void setAge(int age) { this.age = age; }
    public void setOverall(int overall) { this.overall = overall; }

    public int getForm() { return form; }
    public int getMorale() { return morale; }
    public int getYellowCards() { return yellowCards; }
    public int getSuspensionRemaining() { return suspensionRemaining; }

    public void applyForm(int delta) {
        this.form = Math.max(-5, Math.min(5, this.form + delta));
    }

    public void applyMorale(int delta) {
        this.morale = Math.max(-5, Math.min(5, this.morale + delta));
    }

    public void addYellowCard() { this.yellowCards++; }

    public void addSuspension(int matches) { this.suspensionRemaining += matches; }

    public void decrementSuspension() {
        if (this.suspensionRemaining > 0) {
            this.suspensionRemaining--;
        }
    }

    public int getInjuryMatches() { return injuryMatches; }
    public int getFitness() { return fitness; }
    public LocalDate getLastMatchDate() { return lastMatchDate; }
    public InjurySeverity getInjurySeverity() { return injurySeverity; }

    public void addInjury(int matches) { this.injuryMatches += matches; }

    public void tickInjury() {
        if (this.injuryMatches > 0) {
            this.injuryMatches--;
            if (this.injuryMatches == 0) {
                this.injurySeverity = InjurySeverity.NONE;
            }
        }
    }

    /** Persists post-match base fitness and the date the player featured. */
    public void recordAppearance(int newBaseFitness, LocalDate matchDate) {
        Objects.requireNonNull(matchDate, "matchDate must not be null");
        this.fitness = Math.max(0, Math.min(100, newBaseFitness));
        this.lastMatchDate = matchDate;
        this.seasonAppearances++;
    }

    /** Stamps an injury: severity tier + layoff length in matches. */
    public void applyInjury(InjurySeverity severity, int matches) {
        this.injurySeverity = severity;
        this.injuryMatches += matches;
    }

    public boolean isEligible() { return this.suspensionRemaining == 0 && this.injuryMatches == 0; }

    /** Replaces the six anchor attributes + overall after the season aging curve; the other attributes carry over. */
    public void evolveAttributes(int pace, int finishing, int passing,
                                 int tackling, int goalkeeping, int stamina, int overall) {
        this.attributes = this.attributes
                .with(Attribute.VELOCIDADE, pace)
                .with(Attribute.FINALIZACAO, finishing)
                .with(Attribute.PASSE, passing)
                .with(Attribute.DESARME, tackling)
                .with(Attribute.REFLEXOS, goalkeeping)
                .with(Attribute.FOLEGO, stamina);
        this.overall = overall;
    }

    /** Clears per-season state when a player carries over into a new season. */
    public void resetSeasonState() {
        this.form = 0;
        this.morale = 0;
        this.yellowCards = 0;
        this.suspensionRemaining = 0;
        this.injuryMatches = 0;
        this.injurySeverity = InjurySeverity.NONE;
        this.fitness = 100;
        this.lastMatchDate = null;
        this.transferListed = false;
        this.listedReason = null;
        this.releasedByClubId = null;
        this.seasonAppearances = 0;
        this.trainingProgress = 0;
    }

    public BigDecimal getWage() { return wage; }
    public void setWage(BigDecimal wage) { this.wage = wage; }

    public int getContractYears() { return contractYears; }
    public void setContractYears(int contractYears) { this.contractYears = contractYears; }

    public boolean isFreeAgent() { return clubId == null; }

    public boolean isTransferListed() { return transferListed; }
    public ListedReason getListedReason() { return listedReason; }
    public UUID getReleasedByClubId() { return releasedByClubId; }

    public UUID getOwnerClubId() { return ownerClubId; }
    public Integer getLoanWageShare() { return loanWageShare; }
    public Boolean getLoanMayFaceOwner() { return loanMayFaceOwner; }
    public boolean isOnLoan() { return ownerClubId != null; }

    public Attribute getTrainingFocus() { return trainingFocus; }
    public TrainingIntensity getTrainingIntensity() { return trainingIntensity; }

    /** Sets the manager's standing training instruction for this player; a null focus opts out. */
    public void setTrainingAssignment(Attribute focus, TrainingIntensity intensity) {
        this.trainingFocus = focus;
        this.trainingIntensity = intensity == null ? TrainingIntensity.NORMAL : intensity;
    }

    public int getTrainingProgress() { return trainingProgress; }
    public void setTrainingProgress(int trainingProgress) { this.trainingProgress = trainingProgress; }

    public int getSeasonAppearances() { return seasonAppearances; }

    /** Puts the player up for transfer with the given reason; overwrites any prior reason. */
    public void listForTransfer(ListedReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        this.transferListed = true;
        this.listedReason = reason;
    }

    /** Pulls the player off the transfer list. */
    public void unlist() {
        this.transferListed = false;
        this.listedReason = null;
    }

    /**
     * Terminates this player's contract on behalf of {@code clubId}: he leaves the squad as a
     * free agent with no terms, carrying the mark that bars that club from re-signing him this
     * season. The fee, the ledger row and the squad's morale are the service's business.
     */
    public void releaseFrom(UUID clubId) {
        this.clubId = null;
        this.wage = BigDecimal.ZERO;
        this.contractYears = 0;
        this.releasedByClubId = clubId;
        unlist();
    }

    /**
     * Sends this player out on loan: his current club becomes his owner, and he starts playing
     * for {@code borrowerClubId} under the agreed terms. The fee-free nature of a loan, the
     * lineup cleanup and the outstanding-offer rejection are the service's business.
     */
    public void loanTo(UUID borrowerClubId, int wageShare, boolean mayFaceOwner) {
        this.ownerClubId = this.clubId;
        this.clubId = borrowerClubId;
        this.loanWageShare = wageShare;
        this.loanMayFaceOwner = mayFaceOwner;
    }

    /** Ends the loan: he goes home to his owner and carries none of its terms with him. */
    public void returnFromLoan() {
        this.clubId = this.ownerClubId;
        this.ownerClubId = null;
        this.loanWageShare = null;
        this.loanMayFaceOwner = null;
    }
}
