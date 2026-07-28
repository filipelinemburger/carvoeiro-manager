package br.com.carvoeiromanager.player;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the 3050-line generated {@code V43__research_backfill.sql} against writing a value the
 * application cannot read back.
 *
 * <p>The dangerous failure mode here is asymmetric: Postgres happily stores any string in
 * {@code detailed_position} / {@code alt_positions} / {@code familiar_roles}, so a single mistyped
 * enum token in the migration applies CLEANLY — and then throws {@link IllegalArgumentException}
 * out of {@code Enum.valueOf} the first time a save loads that player, long after the migration is
 * irreversibly recorded in {@code flyway_schema_history}. {@link ResearchBackfillMigrationIT}
 * cannot catch that: it reproduces the migration's UPDATE <em>shape</em> with hard-coded literals
 * rather than reading the real file.
 *
 * <p>So this test reads the actual migration off the classpath and pushes every literal it emits
 * through the same parse the read path uses — {@link DetailedPosition#valueOf} (what
 * {@code Player.getAltPositions()} calls) and {@link Player#setFamiliarRoles} (the production
 * validator for role tokens). It needs no database and no Spring context.
 */
class V43ResearchBackfillSqlTest {

    private static final String MIGRATION = "/db/migration/V43__research_backfill.sql";

    /** Matches the SET clause of both statement flavours (club-scoped and free-agent). */
    private static final Pattern STATEMENT = Pattern.compile(
            "detailed_position = '([^']+)',\\s*\\R\\s*alt_positions = (NULL|'[^']+'), familiar_roles = '([^']+)'");

    private record Backfill(DetailedPositionToken primary, List<String> alts, List<String> roles) { }

    /** The raw primary token, kept as text so an invalid one can be reported rather than thrown on. */
    private record DetailedPositionToken(String raw) { }

    private List<Backfill> parseMigration() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            assertThat(in).as("migration %s must be on the classpath", MIGRATION).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Backfill> out = new ArrayList<>();
        Matcher m = STATEMENT.matcher(sql);
        while (m.find()) {
            String alts = m.group(2);
            out.add(new Backfill(
                    new DetailedPositionToken(m.group(1)),
                    "NULL".equals(alts) ? List.of() : Arrays.asList(alts.substring(1, alts.length() - 1).split(",")),
                    Arrays.asList(m.group(3).split(","))));
        }
        return out;
    }

    @Test
    void everyStatementInTheMigrationIsParsedByThisTest() throws IOException {
        // The header advertises 625 club + 179 free-agent statements; if the generator is re-run
        // against changed seed data and the regex stops matching, the checks below would silently
        // verify fewer rows — so pin the count to the number of UPDATE statements in the file.
        String sql;
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        long updates = sql.lines().filter(line -> line.startsWith("UPDATE ")).count();

        assertThat(parseMigration()).hasSize((int) updates);
        assertThat(updates).isEqualTo(804);
    }

    @Test
    void everyDetailedPositionTokenIsAValidEnumConstant() throws IOException {
        for (Backfill backfill : parseMigration()) {
            assertThatCode(() -> DetailedPosition.valueOf(backfill.primary().raw()))
                    .as("primary position '%s'", backfill.primary().raw())
                    .doesNotThrowAnyException();
            for (String alt : backfill.alts()) {
                assertThatCode(() -> DetailedPosition.valueOf(alt))
                        .as("alternate position '%s'", alt)
                        .doesNotThrowAnyException();
            }
        }
    }

    @Test
    void everyFamiliarRoleTokenPassesTheProductionValidator() throws IOException {
        Player scratch = scratchPlayer();
        for (Backfill backfill : parseMigration()) {
            // setFamiliarRoles is exactly what the seed/research path uses: it requires the +/++
            // suffix and resolves the archetype, throwing on anything unknown.
            assertThatCode(() -> scratch.setFamiliarRoles(backfill.roles()))
                    .as("familiar roles %s", backfill.roles())
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void everyStatementHasExactlyOnePrimaryFamiliarRoleAndItComesFirst() throws IOException {
        for (Backfill backfill : parseMigration()) {
            List<String> primaries = backfill.roles().stream().filter(t -> t.endsWith("++")).toList();
            assertThat(primaries).as("'++' tokens in %s", backfill.roles()).hasSize(1);
            assertThat(backfill.roles().get(0)).as("first token of %s", backfill.roles()).endsWith("++");
        }
    }

    @Test
    void alternatesAreDistinctAndNeverRepeatThePrimary() throws IOException {
        for (Backfill backfill : parseMigration()) {
            assertThat(backfill.alts())
                    .as("alternates of %s", backfill.primary().raw())
                    .doesNotContain(backfill.primary().raw())
                    .doesNotHaveDuplicates();
        }
    }

    @Test
    void goalkeepingNeverMixesWithOutfieldPositions() throws IOException {
        String gk = DetailedPosition.GOLEIRO.name();
        for (Backfill backfill : parseMigration()) {
            if (gk.equals(backfill.primary().raw())) {
                assertThat(backfill.alts()).as("a keeper must have no outfield alternates").isEmpty();
            } else {
                assertThat(backfill.alts()).as("an outfielder must not be alternate-keeper").doesNotContain(gk);
            }
        }
    }

    private Player scratchPlayer() {
        return Player.create(UUID.randomUUID(), null, "Scratch", Position.MID, 25, "Brasil",
                50, 50, 50, 50, 50, 50, 60);
    }
}
