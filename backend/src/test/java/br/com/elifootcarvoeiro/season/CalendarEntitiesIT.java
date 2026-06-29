package br.com.elifootcarvoeiro.season;

import br.com.elifootcarvoeiro.auth.User;
import br.com.elifootcarvoeiro.auth.UserRepository;
import br.com.elifootcarvoeiro.career.SaveGame;
import br.com.elifootcarvoeiro.career.SaveGameRepository;
import br.com.elifootcarvoeiro.competition.CompetitionClub;
import br.com.elifootcarvoeiro.competition.CompetitionClubRepository;
import br.com.elifootcarvoeiro.support.PostgresTestContainer;
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
class CalendarEntitiesIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired CompetitionClubRepository competitionClubRepository;
    @Autowired SeasonEventRepository seasonEventRepository;

    private UUID newSave() {
        User u = User.create("cal_" + UUID.randomUUID(), "h");
        userRepository.save(u);
        SaveGame s = SaveGame.create(u.getId(), 2026);
        saveGameRepository.save(s);
        return s.getId();
    }

    @Test
    void membershipRoundTrips() {
        UUID save = newSave();
        UUID comp = UUID.randomUUID();
        UUID club = UUID.randomUUID();
        competitionClubRepository.save(CompetitionClub.create(save, comp, club));
        assertThat(competitionClubRepository.findByCompetitionId(comp))
                .singleElement().satisfies(cc -> assertThat(cc.getClubId()).isEqualTo(club));
        assertThat(competitionClubRepository.findBySaveGameIdAndClubId(save, club)).hasSize(1);
    }

    @Test
    void seasonEventsOrderByDateThenSequenceAndMarkProcessed() {
        UUID save = newSave();
        UUID comp = UUID.randomUUID();
        seasonEventRepository.save(SeasonEvent.createTransfer(save, LocalDate.of(2026, 1, 20), 1));
        seasonEventRepository.save(SeasonEvent.createMatch(save, LocalDate.of(2026, 1, 20), comp, 0, 0));
        seasonEventRepository.save(SeasonEvent.createMatch(save, LocalDate.of(2026, 1, 15), comp, 0, 0));

        SeasonEvent first = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(save);
        assertThat(first.getEventDate()).isEqualTo(LocalDate.of(2026, 1, 15));
        first.markProcessed();
        seasonEventRepository.saveAndFlush(first);

        SeasonEvent next = seasonEventRepository
                .findFirstBySaveGameIdAndProcessedFalseOrderByEventDateAscSequenceAsc(save);
        assertThat(next.getEventDate()).isEqualTo(LocalDate.of(2026, 1, 20));
        assertThat(next.getType()).isEqualTo(SeasonEventType.MATCH); // sequence 0 before TRANSFER seq 1
    }
}
