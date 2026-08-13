package br.com.carvoeiromanager.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SeedLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Competition names to load, or empty for all of them — which is what production always uses.
     *
     * <p>This exists for the test suite. A career seeds every club of every competition and then
     * plays every one of their rounds, so adding Série A put a 20-club, 380-fixture division into
     * all ~100 test careers, and took the backend suite from about 20 minutes to two hours. Almost
     * none of those tests look at Série A. Setting this in the {@code test} profile lets them
     * create the two-competition career they were written against, and the handful that do care
     * about background competitions ask for the full seed back with {@code @TestPropertySource}.
     */
    private final Set<String> competitionFilter;

    /** Loads the whole seed — production, and any test that wants the real thing. */
    public SeedLoader() {
        this("");
    }

    // @Autowired is load-bearing: with two constructors and neither annotated, Spring would
    // quietly choose the no-arg one and the property would never be read.
    @Autowired
    public SeedLoader(@Value("${carvoeiro.seed.competitions:}") String competitionNames) {
        this.competitionFilter = Arrays.stream(competitionNames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public SeedCareer loadCareer() {
        try (InputStream in = new ClassPathResource("seed/clubs.json").getInputStream()) {
            return filtered(mapper.readValue(in, SeedCareer.class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load career seed", e);
        }
    }

    /**
     * Narrows the seed to {@link #competitionFilter}, dropping clubs left with nothing to play.
     *
     * <p>A club is kept when it still belongs to a retained competition, or when it belonged to no
     * competition in the first place — the pool has a deliberate orphan (Hercílio Luz) that exists
     * precisely to prove a club can sit outside every competition, and filtering must not quietly
     * delete it along with the clubs it really is dropping.
     */
    private SeedCareer filtered(SeedCareer career) {
        if (competitionFilter.isEmpty()) {
            return career;
        }
        Set<String> unknown = competitionFilter.stream()
                .filter(name -> career.competitions().stream().noneMatch(c -> c.name().equals(name)))
                .collect(Collectors.toSet());
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "carvoeiro.seed.competitions names competitions that are not in the seed: "
                            + unknown + " — the filter is a typo away from silently loading nothing");
        }

        List<SeedCompetition> kept = career.competitions().stream()
                .filter(c -> competitionFilter.contains(c.name()))
                .toList();
        Set<String> keptMembers = kept.stream()
                .flatMap(c -> c.memberShortNames().stream())
                .collect(Collectors.toSet());
        Set<String> anyMember = career.competitions().stream()
                .flatMap(c -> c.memberShortNames().stream())
                .collect(Collectors.toSet());

        List<SeedClub> clubs = career.clubPool().stream()
                .filter(c -> keptMembers.contains(c.shortName()) || !anyMember.contains(c.shortName()))
                .toList();
        return new SeedCareer(clubs, kept);
    }

    public NewgenNames loadNewgenNames() {
        try (InputStream in = new ClassPathResource("seed/newgen-names.json").getInputStream()) {
            return mapper.readValue(in, NewgenNames.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load newgen names seed", e);
        }
    }

    /** The real, hand-authored South American free-agent pool. Career creation seeds the
     *  free-agent pool 1:1 from this list — there is no procedural generation. */
    public List<SeedPlayer> loadFreeAgents() {
        try (InputStream in = new ClassPathResource("seed/free-agents.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<SeedPlayer>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load free-agent seed", e);
        }
    }

    /** The real, hand-authored technical-staff hiring pool; candidates draw from it before
     *  falling back to generated names. */
    public List<SeedStaff> loadStaffPool() {
        try (InputStream in = new ClassPathResource("seed/staff-pool.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<SeedStaff>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load staff pool seed", e);
        }
    }

    /** The researched coaching staff each club already employs when a career begins. Coverage is
     *  deliberately partial — an absent role means an open slot, not missing data. */
    public List<SeedClubStaff> loadClubStaff() {
        try (InputStream in = new ClassPathResource("seed/club-staff.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<List<SeedClubStaff>>() {});
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load club staff seed", e);
        }
    }
}
