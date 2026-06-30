package br.com.carvoeiromanager.season;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.support.PostgresTestContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class RolloverCalendarIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired SeasonAdvanceService advanceService;
    @Autowired SeasonRolloverService rolloverService;
    @Autowired SeasonEventRepository seasonEventRepository;

    @Test
    void rolloverRegeneratesADatedCalendarForTheNewSeason() {
        User u = User.create("rc_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGameResponse save = saveGameService.createCareer(u.getId());
        int guard = 0;
        while (saveGameRepository.findById(save.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            advanceService.advance(save.id(), u.getId());
        }
        rolloverService.rollover(save.id(), u.getId());

        SaveGame s = saveGameRepository.findById(save.id()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(SaveGameStatus.IN_PROGRESS);
        assertThat(s.getCurrentDate()).isEqualTo(LocalDate.of(save.seasonYear() + 1, 1, 15));
        // A fresh calendar of unprocessed events exists for the new season.
        var events = seasonEventRepository.findBySaveGameIdAndProcessedFalse(save.id());
        assertThat(events.stream().anyMatch(e -> e.getType() == SeasonEventType.MATCH)).isTrue();
        assertThat(events).allMatch(e -> e.getEventDate().getYear() == save.seasonYear() + 1);
    }
}
