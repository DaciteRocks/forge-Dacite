package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/**
 * Tier-1 unit tests for the pure dialog wire-logic in {@link WebDialogs}: option building and the
 * index/boolean/amount reply parsers, including the fallbacks that keep a mandatory choice valid.
 */
public class WebDialogsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(final String s) throws Exception {
        return MAPPER.readTree(s);
    }

    @Test
    public void optionsCarryIndexAndLabel() {
        final List<Map<String, Object>> opts = WebDialogs.options(List.of("Alpha", "Beta"));
        Assert.assertEquals(opts.size(), 2);
        Assert.assertEquals(opts.get(0).get("index"), 0);
        Assert.assertEquals(opts.get(1).get("label"), "Beta");
        Assert.assertTrue(WebDialogs.options(null).isEmpty(), "null labels -> empty option list");
    }

    @Test
    public void replyIndexParsesAndDefaultsToMinusOne() throws Exception {
        Assert.assertEquals(WebDialogs.replyIndex(json("{\"index\":3}")), 3);
        Assert.assertEquals(WebDialogs.replyIndex(json("{}")), -1, "missing index -> -1 (cancel)");
        Assert.assertEquals(WebDialogs.replyIndex(null), -1, "null reply -> -1");
    }

    @Test
    public void replyIndicesAndIntArray() throws Exception {
        Assert.assertEquals(WebDialogs.replyIndices(json("{\"indices\":[0,2,5]}")), List.of(0, 2, 5));
        Assert.assertTrue(WebDialogs.replyIndices(json("{}")).isEmpty());
        Assert.assertEquals(WebDialogs.intArray(json("{\"amounts\":[1,0,4]}"), "amounts"), List.of(1, 0, 4));
    }

    @Test
    public void replyBoolHonorsDefault() throws Exception {
        Assert.assertTrue(WebDialogs.replyBool(json("{\"value\":true}"), false));
        Assert.assertFalse(WebDialogs.replyBool(json("{\"value\":false}"), true));
        Assert.assertTrue(WebDialogs.replyBool(json("{}"), true), "missing value -> default");
        Assert.assertFalse(WebDialogs.replyBool(null, false), "null reply -> default");
    }

    @Test
    public void sanitizeSelectionDropsOutOfRangeAndDedups() {
        // 9 and -1 are out of range for size 3; 1 is duplicated.
        Assert.assertEquals(WebDialogs.sanitizeSelection(List.of(1, 9, 1, -1, 2), 0, 3), List.of(1, 2));
    }

    @Test
    public void sanitizeSelectionFallsBackToFirstMinWhenTooFew() {
        // Empty (cancelled) reply with min 2 -> first two indices so a mandatory choice is honored.
        Assert.assertEquals(WebDialogs.sanitizeSelection(List.of(), 2, 4), List.of(0, 1));
    }

    @Test
    public void validateDistributionAcceptsExactSumAndClampsNegatives() {
        Assert.assertEquals(WebDialogs.validateDistribution(List.of(2, 1), 2, 3, 0), List.of(2, 1));
        // A negative clamps to 0, but then the sum no longer matches -> rejected (prevents desync
        // where the JS running total counted the raw negative).
        Assert.assertNull(WebDialogs.validateDistribution(List.of(5, -2), 2, 3, 0),
                "clamping a negative changes the sum -> reject rather than silently misassign");
    }

    @Test
    public void validateDistributionRejectsWrongSizeOrSum() {
        Assert.assertNull(WebDialogs.validateDistribution(List.of(1, 1), 3, 2, 0), "wrong length -> null");
        Assert.assertNull(WebDialogs.validateDistribution(List.of(1, 1), 2, 3, 0), "wrong sum -> null");
        Assert.assertNull(WebDialogs.validateDistribution(null, 2, 3, 0), "no reply -> null");
    }

    @Test
    public void validateDistributionEnforcesPerTargetMinimum() {
        // atLeastOne (minEach=1): every target must get >= 1.
        Assert.assertEquals(WebDialogs.validateDistribution(List.of(1, 2), 2, 3, 1), List.of(1, 2));
        Assert.assertNull(WebDialogs.validateDistribution(List.of(3, 0), 2, 3, 1),
                "a zero target violates at-least-one");
    }
}
