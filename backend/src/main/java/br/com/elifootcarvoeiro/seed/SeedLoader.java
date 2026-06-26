package br.com.elifootcarvoeiro.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class SeedLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    public SeedData loadCatarinense() {
        try (InputStream in = new ClassPathResource("seed/catarinense.json").getInputStream()) {
            return mapper.readValue(in, SeedData.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load catarinense seed", e);
        }
    }
}
