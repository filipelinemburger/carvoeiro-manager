package br.com.carvoeiromanager.transfer;

import br.com.carvoeiromanager.auth.User;
import br.com.carvoeiromanager.auth.UserRepository;
import br.com.carvoeiromanager.career.SaveGameResponse;
import br.com.carvoeiromanager.career.SaveGameService;
import br.com.carvoeiromanager.career.SaveGameRepository;
import br.com.carvoeiromanager.career.SaveGameStatus;
import br.com.carvoeiromanager.club.Club;
import br.com.carvoeiromanager.club.ClubRepository;
import br.com.carvoeiromanager.player.Player;
import br.com.carvoeiromanager.player.PlayerRepository;
import br.com.carvoeiromanager.season.AdvanceResponse;
import br.com.carvoeiromanager.season.NewsItem;
import br.com.carvoeiromanager.season.NewsItemRepository;
import br.com.carvoeiromanager.season.SeasonAdvanceService;
import br.com.carvoeiromanager.season.SeasonRolloverService;
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
class TransferAdvanceRolloverIT {

    @Autowired SeasonAdvanceService seasonAdvanceService;
    @Autowired SeasonRolloverService seasonRolloverService;
    @Autowired SaveGameService saveGameService;
    @Autowired SaveGameRepository saveGameRepository;
    @Autowired UserRepository userRepository;
    @Autowired ClubRepository clubRepository;
    @Autowired PlayerRepository playerRepository;
    @Autowired TransferOfferRepository transferOfferRepository;
    @Autowired NewsItemRepository newsItemRepository;
    @Autowired tools.jackson.databind.ObjectMapper objectMapper;

    private record Career(UUID userId, SaveGameResponse save) {
        UUID id() { return save.id(); }
        UUID clubId() { return save.managedClubId(); }
    }

    private Career createCareer() {
        User user = User.create("txadv_" + UUID.randomUUID(), "hash");
        userRepository.save(user);
        return new Career(user.getId(), saveGameService.createCareer(user.getId()));
    }

    private static final List<TransferOfferStatus> ALL = List.of(
            TransferOfferStatus.PENDING, TransferOfferStatus.COUNTERED, TransferOfferStatus.ACCEPTED,
            TransferOfferStatus.REJECTED, TransferOfferStatus.WITHDRAWN);

    @Test
    void advancingInsideAWindowProducesOnlyValidIncomingOffers() {
        // Given a fresh career, the early calendar lands inside the pre-season transfer window,
        // so incoming bids are generated on the TRANSFER events the advance pops.
        Career c = createCareer();

        // When advancing through the first several events (which include pre-season TRANSFER stops)
        for (int i = 0; i < 6; i++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            // Stop once the season would finish (defensive; never reached this early).
            if (r.seasonFinished()) {
                break;
            }
        }

        // Then any generated offers ON the managed club are INCOMING, PENDING, for the managed
        // club, and bounded. AI-to-AI trades (direction AI, always ACCEPTED, never involving the
        // managed club) are a separate, unrelated part of the same market day and are excluded
        // from this assertion by construction.
        List<TransferOffer> offers = transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL).stream()
                .filter(o -> o.getDirection() != TransferDirection.AI)
                .toList();
        assertThat(offers).allMatch(o -> o.getDirection() == TransferDirection.INCOMING);
        assertThat(offers).allMatch(o -> o.getStatus() == TransferOfferStatus.PENDING);
        assertThat(offers).allMatch(o -> o.getToClubId().equals(c.clubId()));
        // Up to MAX_BIDS_PER_ROUND (3) bids per processed market day; createCareer's day-one run
        // plus up to 6 more advance() calls this early is at most 7 market days.
        assertThat(offers.size()).isLessThanOrEqualTo(21);
    }

    /**
     * End-to-end: whenever a TRANSFER advance's market day completes an AI-AI trade, it surfaces
     * identically in the live {@link AdvanceResponse#transfers()} and, round-tripped through
     * JSON, in the persisted news payload. Uses a stock (uncustomised) career and career squads
     * routinely produce at least one organic AI-AI trade within a handful of market days — this
     * exercises the real {@code AiListingEngine -> AiTransferEngine -> news} pipeline end to end
     * rather than a hand-scripted pair (which {@link AiTransferEngineIT} already covers in
     * isolation, without the match-simulation and listing-engine interplay a full advance carries).
     */
    @Test
    void aTransferAdvanceThatCompletesAnAiTradeSurfacesItInTheResponseAndTheNewsPayload() {
        Career c = createCareer();

        boolean verified = false;
        for (int i = 0; i < 20 && !verified; i++) {
            AdvanceResponse r = seasonAdvanceService.advance(c.id(), c.userId());
            // transfers() must never be null on a freshly produced response (only OLD, persisted
            // payloads from before this field existed deserialize it as null).
            assertThat(r.transfers()).isNotNull();

            // Whatever this advance produced (including transfers()) round-trips through the
            // news payload that was just persisted for it.
            NewsItem justPersisted = newsItemRepository.findBySaveGameIdOrderBySeqDesc(c.id()).get(0);
            AdvanceResponse decoded = deserialize(justPersisted.getPayload());
            assertThat(decoded.transfers()).isEqualTo(r.transfers());

            if (!r.transfers().isEmpty()) {
                verified = true;
                for (CompletedTransferResponse trade : r.transfers()) {
                    assertThat(trade.playerName()).isNotBlank();
                    assertThat(trade.fromClubName()).isNotBlank();
                    assertThat(trade.toClubName()).isNotBlank();
                    assertThat(trade.fromClubName()).isNotEqualTo(trade.toClubName());
                    assertThat(trade.fee().signum()).isPositive();
                }
            }
        }

        // Non-vacuous: at least one of the swept market days actually completed a trade (career
        // squads routinely produce one within a handful of days), so the transfers()-populated
        // branch above genuinely ran at least once.
        assertThat(verified).isTrue();
    }

    private AdvanceResponse deserialize(String payload) {
        try {
            return objectMapper.readValue(payload, AdvanceResponse.class);
        } catch (Exception e) {
            throw new AssertionError("Stored news payload is not valid AdvanceResponse JSON: " + payload, e);
        }
    }

    @Test
    void rolloverPurgesTransferOffers() {
        // Given a career with at least one transfer offer
        Career c = createCareer();
        Player managedPlayer = playerRepository.findByClubId(c.clubId()).get(0);
        UUID aiClub = clubRepository.findBySaveGameId(c.id()).stream()
                .map(Club::getId).filter(id -> !id.equals(c.clubId())).findFirst().orElseThrow();
        transferOfferRepository.save(TransferOffer.create(c.id(), managedPlayer.getId(), aiClub,
                c.clubId(), TransferDirection.INCOMING, new BigDecimal("900000.00"), 0, null, null));

        // When the season is played out and rolled over
        int guard = 0;
        while (saveGameRepository.findById(c.id()).orElseThrow().getStatus() != SaveGameStatus.FINISHED
                && guard++ < 500) {
            seasonAdvanceService.advance(c.id(), c.userId());
        }
        seasonRolloverService.rollover(c.id(), c.userId());

        // Then all transfer offers for the save are purged
        assertThat(transferOfferRepository.findBySaveGameIdAndStatusIn(c.id(), ALL)).isEmpty();
    }
}
