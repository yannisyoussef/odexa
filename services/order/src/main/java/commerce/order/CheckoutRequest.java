package commerce.order;

import commerce.runtime.ApiException;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Compatibility transport only: both forms become one canonical purchase intent. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutRequest(UUID productId, Integer quantity, List<Item> items, String paymentMethod) {
    @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
    public CheckoutRequest { }

    /** Presence matters: null is not a way to mix the legacy and basket forms. */
    @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
    public static CheckoutRequest fromJson(tools.jackson.databind.JsonNode json) {
        if (json == null || !json.isObject()) throw invalid();
        var method = json.get("paymentMethod");
        if (method == null || !method.isString()) throw invalid();
        if (json.has("items")) {
            var items = json.get("items");
            if (json.size() != 2 || items == null || !items.isArray() || items.isEmpty() || items.size() > 20) throw invalid();
            var lines = new ArrayList<Item>();
            for (var item : items) {
                if (!item.isObject() || item.size() != 2) throw invalid();
                lines.add(readItem(item));
            }
            return new CheckoutRequest(lines,method.stringValue());
        }
        if (json.size() != 3) throw invalid();
        Item item = readItem(json);
        return new CheckoutRequest(item.productId(),item.quantity(),method.stringValue());
    }
    private static Item readItem(tools.jackson.databind.JsonNode json) {
        var id = json.get("productId"); var quantity = json.get("quantity");
        if (id == null || !id.isString() || quantity == null || !quantity.isIntegralNumber()
                || !quantity.canConvertToInt()) throw invalid();
        try {
            UUID uuid = UUID.fromString(id.stringValue());
            if (!uuid.toString().equalsIgnoreCase(id.stringValue())) throw invalid();
            return new Item(uuid,quantity.intValue());
        } catch (IllegalArgumentException error) { throw invalid(); }
    }

    public record Item(UUID productId, int quantity) { }
    public CheckoutRequest(UUID productId, int quantity, String paymentMethod) {
        this(productId, quantity, null, paymentMethod);
    }
    public CheckoutRequest(List<Item> items, String paymentMethod) {
        this(null, null, items, paymentMethod);
    }
    public void validate() { normalizedItems(); }
    public List<Item> normalizedItems() {
        if (paymentMethod == null || !paymentMethod.matches("pm_[A-Za-z0-9_]{1,125}")) throw invalid();
        List<Item> lines;
        if (items != null) {
            if (productId != null || quantity != null) throw invalid();
            lines = items;
        } else {
            if (productId == null || quantity == null) throw invalid();
            lines = List.of(new Item(productId, quantity));
        }
        if (lines.isEmpty() || lines.size() > 20)
            throw new ApiException(400, "INVALID_ITEM_COUNT", "Checkout requires 1 to 20 distinct items");
        Set<UUID> ids = new HashSet<>();
        for (Item line : lines) {
            if (line == null || line.productId() == null || line.quantity() < 1 || line.quantity() > 100) throw invalid();
            if (!ids.add(line.productId()))
                throw new ApiException(400, "DUPLICATE_ORDER_ITEM", "Each product must appear once");
        }
        return lines.stream().sorted(Comparator.comparing(i -> i.productId().toString())).toList();
    }
    public String fingerprint() {
        List<Item> lines = normalizedItems();
        // Keep the released single-line hash for both input forms; existing keys still replay.
        String canonical = lines.size() == 1
                ? lines.getFirst().productId() + "\n" + lines.getFirst().quantity() + "\n" + paymentMethod
                : "basket-v2\n" + lines.stream().map(i -> i.productId() + ":" + i.quantity())
                    .collect(java.util.stream.Collectors.joining("\n")) + "\n" + paymentMethod;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private static ApiException invalid() { return new ApiException(400, "INVALID_CHECKOUT", "Invalid checkout request"); }
}
