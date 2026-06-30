package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGame;
import br.com.carvoeiromanager.career.SaveGameRepository;
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

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestContainer.class)
class TransferOfferEntityIT {

    @Autowired UserRepository userRepository;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired TransferOfferRepository transferOfferRepository;

    @Test
    void persistsAndTransitionsATransferOffer() {
        // Given a save game and an outgoing offer
        User user = User.create("tx_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        SaveGame save = SaveGame.create(user.getId(), 2026);
        saveGameRepository.save(save);

        TransferOffer offer = TransferOffer.create(save.getId(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), TransferDirection.OUTGOING,
                new BigDecimal("1500000.00"), 0, null, null);
        transferOfferRepository.save(offer);

        // When it is countered and read back
        offer.markCountered(new BigDecimal("1800000.00"));
        transferOfferRepository.save(offer);
        List<TransferOffer> active = transferOfferRepository.findBySaveGameIdAndStatusIn(
                save.getId(), List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED));

        // Then it round-trips with the counter and COUNTERED status
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStatus()).isEqualTo(TransferOfferStatus.COUNTERED);
        assertThat(active.get(0).getCounterAmount()).isEqualByComparingTo("1800000.00");
        assertThat(active.get(0).getDirection()).isEqualTo(TransferDirection.OUTGOING);

        // And deleteBySaveGameId purges it
        transferOfferRepository.deleteBySaveGameId(save.getId());
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(save.getId(),
                List.of(TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED))).isEmpty();
    }
}
