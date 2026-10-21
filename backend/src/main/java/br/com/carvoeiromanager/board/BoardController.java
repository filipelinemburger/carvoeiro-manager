package br.com.carvoeiromanager.board;

import br.com.carvoeiromanager.common.security.CurrentUser;
import br.com.carvoeiromanager.career.SaveGameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Where the manager stands with the board. Read-only, so it stays open on a sacked save. */
@RestController
@RequestMapping("/api/v1/save-games/{saveGameId}/board")
public class BoardController {

    private final SaveGameService saveGameService;
    private final BoardService boardService;

    public BoardController(SaveGameService saveGameService, BoardService boardService) {
        this.saveGameService = saveGameService;
        this.boardService = boardService;
    }

    @GetMapping
    public BoardResponse get(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return boardService.getBoard(saveGameService.requireOwned(saveGameId, userId));
    }

    @GetMapping("/history")
    public List<BoardSeasonRecord> history(@PathVariable UUID saveGameId, @CurrentUser UUID userId) {
        return boardService.history(saveGameService.requireOwned(saveGameId, userId));
    }
}
