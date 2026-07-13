package forge.gui.web;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for the interactive decision-dialog round-trips: building the option payloads the
 * browser renders, and parsing the index/boolean/amount replies it sends back. Kept engine-free
 * (only Jackson + collections) so the wire logic is unit-testable without booting a game.
 */
final class WebDialogs {
    private WebDialogs() {
    }

    /** Build {@code [{index, label}]} option maps from a list of display labels. */
    static List<Map<String, Object>> options(final List<String> labels) {
        final List<Map<String, Object>> out = new ArrayList<>();
        if (labels != null) {
            for (int i = 0; i < labels.size(); i++) {
                final Map<String, Object> o = new LinkedHashMap<>();
                o.put("index", i);
                o.put("label", labels.get(i));
                out.add(o);
            }
        }
        return out;
    }

    /** Single chosen index from {@code {"index": n}}, or -1 if absent/blank/cancelled. */
    static int replyIndex(final JsonNode reply) {
        if (reply == null) {
            return -1;
        }
        final JsonNode n = reply.get("index");
        return n != null && n.canConvertToInt() ? n.asInt() : -1;
    }

    /** Chosen indices from {@code {"indices": [...]}}; empty if none. */
    static List<Integer> replyIndices(final JsonNode reply) {
        final List<Integer> out = new ArrayList<>();
        if (reply != null) {
            final JsonNode arr = reply.get("indices");
            if (arr != null && arr.isArray()) {
                for (final JsonNode n : arr) {
                    if (n.canConvertToInt()) {
                        out.add(n.asInt());
                    }
                }
            }
        }
        return out;
    }

    /** A named int-array field (e.g. {@code "amounts"}); non-integers become 0. Empty if absent. */
    static List<Integer> intArray(final JsonNode reply, final String field) {
        final List<Integer> out = new ArrayList<>();
        if (reply != null) {
            final JsonNode arr = reply.get(field);
            if (arr != null && arr.isArray()) {
                for (final JsonNode n : arr) {
                    out.add(n.canConvertToInt() ? n.asInt() : 0);
                }
            }
        }
        return out;
    }

    /** Boolean reply from {@code {"value": true/false}}, or {@code def} if absent/non-boolean. */
    static boolean replyBool(final JsonNode reply, final boolean def) {
        if (reply == null) {
            return def;
        }
        final JsonNode n = reply.get("value");
        return n != null && n.isBoolean() ? n.asBoolean() : def;
    }

    /**
     * Keep only in-range, de-duplicated indices; if fewer than {@code min} survive (a short or
     * cancelled reply), fall back to the first {@code min} indices so a mandatory choice is honored.
     */
    static List<Integer> sanitizeSelection(final List<Integer> chosen, final int min, final int size) {
        final List<Integer> valid = new ArrayList<>();
        for (final int i : chosen) {
            if (i >= 0 && i < size && !valid.contains(i)) {
                valid.add(i);
            }
        }
        if (valid.size() < min) {
            valid.clear();
            for (int i = 0; i < size && valid.size() < min; i++) {
                valid.add(i);
            }
        }
        return valid;
    }

    /**
     * Validate a distribution reply: it must have exactly {@code size} entries, each at least
     * {@code minEach} after clamping negatives to zero, summing to {@code total}. Returns the
     * clamped amounts, or {@code null} if invalid (the caller then falls back to a safe default).
     */
    static List<Integer> validateDistribution(final List<Integer> amounts, final int size,
                                              final int total, final int minEach) {
        if (amounts == null || amounts.size() != size) {
            return null;
        }
        final List<Integer> clamped = new ArrayList<>();
        int sum = 0;
        for (final int a : amounts) {
            final int v = Math.max(0, a);
            if (v < minEach) {
                return null; // a required-at-least-one target got nothing
            }
            clamped.add(v);
            sum += v;
        }
        return sum == total ? clamped : null;
    }
}
