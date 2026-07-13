package forge.gui.web.dto;

import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;

import java.util.ArrayList;
import java.util.List;

/** JSON projection of a stack entry, including its source card so the client can show its detail. */
public final class StackItemDto {
    public int id;
    public String text;
    public Integer sourceCardId;
    public CardViewDto card;

    public static StackItemDto from(final StackItemView s, final Iterable<PlayerView> viewers) {
        if (s == null) {
            return null;
        }
        final StackItemDto d = new StackItemDto();
        d.id = s.getId();
        d.text = s.getText();
        d.sourceCardId = s.getSourceCard() != null ? s.getSourceCard().getId() : null;
        d.card = CardViewDto.from(s.getSourceCard(), viewers);
        return d;
    }

    public static List<StackItemDto> fromAll(final Iterable<StackItemView> stack, final Iterable<PlayerView> viewers) {
        if (stack == null) {
            return null;
        }
        final List<StackItemDto> out = new ArrayList<>();
        for (final StackItemView s : stack) {
            out.add(from(s, viewers));
        }
        return out;
    }
}
