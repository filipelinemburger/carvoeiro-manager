package br.com.carvoeiromanager.seed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class SeedLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    public SeedCareer loadCareer() {
        try (InputStream in = new ClassPathResource("seed/clubs.json").getInputStream()) {
            return mapper.readValue(in, SeedCareer.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load career seed", e);
        }
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
}
