package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.player.Attribute;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.player.TrainingIntensity;
import br.com.carvoeiromanager.staff.Staff;
import br.com.carvoeiromanager.staff.StaffQuality;
import br.com.carvoeiromanager.staff.StaffRepository;
import br.com.carvoeiromanager.staff.StaffRole;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the weekly training report reaches the persisted news feed as its own
 * TRAINING_REPORT item, and that reporting stays gated on a hired Preparador Físico.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TrainingReportNewsIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SaveGameService saveGameService;
    @Autowired UserRepository userRepository;
    @Autowired NewsItemRepository newsItemRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired StaffRepository staffRepository;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("trainrep_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    @Test
    void anEvolutionWeekPersistsATrainingReportNewsItem() throws Exception {
        // Given a hired EXCELENTE physio (also grants coach bonus +2) and a managed player one
        // unit from evolving: LEVE(+1) + coach(+2) pushes 39 → 42, guaranteeing an evolution.
        Career c = createCareer();
        staffRepository.save(Staff.create(c.id(), c.clubId(), StaffRole.PREPARADOR_FISICO,
                StaffQuality.EXCELENTE, "Preparador Teste", new BigDecimal("80000")));
        Player trainee = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        trainee.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        trainee.setTrainingProgress(39);
        playerRepository.save(trainee);

        // When advancing once (the first advance always crosses into a fresh training week)
        seasonAdvanceService.advance(c.id(), c.userId());

        // Then a TRAINING_REPORT news item was persisted alongside the advance's own item
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        NewsItem reportItem = items.stream()
                .filter(i -> i.getType().equals("TRAINING_REPORT"))
                .findFirst().orElseThrow();

        // And its payload round-trips with the report populated and pt-BR message text
        AdvanceResponse payload = objectMapper.readValue(reportItem.getPayload(), AdvanceResponse.class);
        assertThat(payload.type()).isEqualTo("TRAINING_REPORT");
        assertThat(payload.trainingReport()).isNotNull();
        assertThat(payload.trainingReport().staffName()).isEqualTo("Preparador Teste");
        assertThat(payload.trainingReport().quality()).isEqualTo("EXCELENTE");
        assertThat(payload.trainingReport().entries())
                .anySatisfy(e -> assertThat(e.message()).contains("evoluiu em Passe"));
    }

    @Test
    void noPhysioMeansNoTrainingReportItem() {
        // Given the same evolving player but no staff hired at all
        Career c = createCareer();
        Player trainee = playerRepository.findByClubIdAndYouthProspectFalse(c.clubId()).get(0);
        trainee.setTrainingAssignment(Attribute.PASSE, TrainingIntensity.LEVE);
        trainee.setTrainingProgress(39);
        playerRepository.save(trainee);

        // When advancing once
        seasonAdvanceService.advance(c.id(), c.userId());

        // Then no TRAINING_REPORT item exists (the tick itself still ran)
        List<NewsItem> items = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id());
        assertThat(items).noneMatch(i -> i.getType().equals("TRAINING_REPORT"));
        Player after = playerRepository.findById(trainee.getId()).orElseThrow();
        assertThat(after.getPassing()).isGreaterThanOrEqualTo(trainee.getPassing());
        assertThat(after.getTrainingProgress()).isLessThan(39); // meter consumed by the tick
    }

    @Test
    void oldNewsPayloadsWithoutTheFieldStillDeserialize() throws Exception {
        // Given a pre-feature MATCH payload with no trainingReport key
        String legacy = """
                {"type":"MATCH","date":"2026-03-01","competitionName":"Camp","round":0,
                 "results":[],"bookings":[],"newSuspensions":[],"newInjuries":[],
                 "finance":null,"bidsReceived":0,"seasonFinished":false}
                """;

        AdvanceResponse parsed = objectMapper.readValue(legacy, AdvanceResponse.class);

        assertThat(parsed.trainingReport()).isNull();
    }
}
