package br.com.elifootcarvoeiro;

import br.com.elifootcarvoeiro.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class ApplicationBootTest {
    @Test
    void contextLoadsAndMigrationsApply() {
        // Given/When: the Spring context starts with Flyway against a real Postgres
        // Then: no exception is thrown (assertion is the successful startup)
    }
}
