package forge.gui.web.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import forge.game.card.CounterType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tier-1 unit tests for the JSON contract the browser depends on. These build DTOs directly (no
 * engine) and assert the serialized field names/shape, plus the null/empty behavior of the pure
 * helpers. The {@code from(View)} mappers themselves are exercised end-to-end by the Tier-2
 * {@link forge.gui.web.WebGameBootstrapTest}; here we pin the wire shape those mappers must produce.
 */
public class DtoShapeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    public void cardViewDtoSerializesBoardFields() throws Exception {
        final CardViewDto c = new CardViewDto();
        c.id = 7;
        c.name = "Grizzly Bears";
        c.type = "Creature — Bear";
        c.power = 2;
        c.toughness = 2;
        c.damage = 1;
        c.creature = true;
        c.tapped = true;
        c.sick = true;
        c.attachedCardIds = List.of(11, 12);
        c.counters = new LinkedHashMap<>();
        c.counters.put("P1P1", 3);

        final JsonNode n = MAPPER.readTree(MAPPER.writeValueAsString(c));
        Assert.assertEquals(n.get("id").asInt(), 7);
        Assert.assertEquals(n.get("name").asText(), "Grizzly Bears");
        Assert.assertEquals(n.get("power").asInt(), 2);
        Assert.assertEquals(n.get("damage").asInt(), 1);
        Assert.assertTrue(n.get("tapped").asBoolean());
        Assert.assertTrue(n.get("sick").asBoolean());
        Assert.assertEquals(n.get("counters").get("P1P1").asInt(), 3);
        Assert.assertEquals(n.get("attachedCardIds").size(), 2);
        Assert.assertEquals(n.get("attachedCardIds").get(0).asInt(), 11);
    }

    @Test
    public void combatDtoFromNullIsNull() {
        Assert.assertNull(CombatDto.from(null), "no CombatView -> no combat in the payload");
    }

    @Test
    public void gameViewDtoWithCombatSerializesBands() throws Exception {
        final CombatDto.Band band = new CombatDto.Band();
        band.attackerId = 1;
        band.attackerName = "Attacker";
        band.defenderId = 99;
        band.defenderName = "Defender";
        band.blockerIds = List.of(2, 3);

        final CombatDto combat = new CombatDto();
        combat.bands = List.of(band);

        final GameViewDto g = new GameViewDto();
        g.turn = 4;
        g.phase = "COMBAT_DECLARE_BLOCKERS";
        g.combat = combat;

        final JsonNode n = MAPPER.readTree(MAPPER.writeValueAsString(g));
        final JsonNode b0 = n.get("combat").get("bands").get(0);
        Assert.assertEquals(b0.get("attackerName").asText(), "Attacker");
        Assert.assertEquals(b0.get("defenderName").asText(), "Defender");
        Assert.assertEquals(b0.get("blockerIds").size(), 2);
    }

    @Test
    public void idsOfHandlesNullAndEmpty() {
        Assert.assertNull(CardViewDto.idsOf(null), "null collection -> null");
        Assert.assertNull(CardViewDto.idsOf(new ArrayList<>()), "empty collection -> null (field omitted-ish)");
    }

    @Test
    public void counterMapHandlesNullEmptyAndEntries() {
        Assert.assertNull(CardViewDto.counterMap(null), "null counters -> null");
        Assert.assertNull(CardViewDto.counterMap(new LinkedHashMap<>()), "empty counters -> null");

        final Map<CounterType, Integer> src = new LinkedHashMap<>();
        src.put(CounterType.getType("P1P1"), 2);
        final Map<String, Integer> out = CardViewDto.counterMap(src);
        Assert.assertNotNull(out);
        Assert.assertEquals(out.get("P1P1").intValue(), 2, "counter type serializes to its name key");
    }
}
