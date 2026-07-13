package forge.gui.web.dto;

import forge.game.GameView;
import forge.game.player.PlayerView;

import java.util.ArrayList;
import java.util.List;

/** JSON projection of the whole {@link GameView} — the full-state snapshot pushed on every update. */
public final class GameViewDto {
    public String title;
    public int turn;
    public String phase;
    public boolean commander;
    public boolean gameOver;
    public String winner;
    public Integer playerTurnId;
    public List<PlayerViewDto> players;
    public List<StackItemDto> stack;
    public CombatDto combat;

    public static GameViewDto from(final GameView g) {
        if (g == null) {
            return null;
        }
        final GameViewDto d = new GameViewDto();
        d.title = g.getTitle();
        d.turn = g.getTurn();
        d.phase = g.getPhase() != null ? g.getPhase().name() : null;
        d.commander = g.isCommander();
        d.gameOver = g.isGameOver();
        d.winner = g.getWinningPlayerName();
        final PlayerView turnPlayer = g.getPlayerTurn();
        d.playerTurnId = turnPlayer != null ? turnPlayer.getId() : null;

        d.players = new ArrayList<>();
        if (g.getPlayers() != null) {
            for (final PlayerView p : g.getPlayers()) {
                d.players.add(PlayerViewDto.from(p));
            }
        }
        d.stack = StackItemDto.fromAll(g.getStack());
        d.combat = CombatDto.from(g.getCombat());
        return d;
    }
}
