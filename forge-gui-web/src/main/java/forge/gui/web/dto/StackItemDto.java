package forge.gui.web.dto;

import forge.game.spellability.StackItemView;

import java.util.ArrayList;
import java.util.List;

/** JSON projection of a stack entry. */
public final class StackItemDto {
    public int id;
    public String text;
    public Integer sourceCardId;

    public static StackItemDto from(final StackItemView s) {
        if (s == null) {
            return null;
        }
        final StackItemDto d = new StackItemDto();
        d.id = s.getId();
        d.text = s.getText();
        d.sourceCardId = s.getSourceCard() != null ? s.getSourceCard().getId() : null;
        return d;
    }

    public static List<StackItemDto> fromAll(final Iterable<StackItemView> stack) {
        if (stack == null) {
            return null;
        }
        final List<StackItemDto> out = new ArrayList<>();
        for (final StackItemView s : stack) {
            out.add(from(s));
        }
        return out;
    }
}
