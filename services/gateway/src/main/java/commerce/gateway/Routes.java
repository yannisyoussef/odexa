package commerce.gateway;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only these resource paths may cross the edge. No URI comes from a caller. */
@Component
final class Routes {
    private static final String ID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern ITEM = Pattern.compile("/api/v1/(products|inventory|orders|payments)/" + ID);
    private static final Pattern MERCHANT_ORDER = Pattern.compile("/api/v1/merchant/orders(?:/" + ID + "(?:/history)?)?");
    private static final Pattern ORDER_HISTORY = Pattern.compile("/api/v1/orders/" + ID + "/history");
    private static final Pattern ORDER_CANCEL = Pattern.compile("/api/v1/orders/" + ID + "/cancel");
    private static final Set<String> HEALTH = Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");
    private final Map<String, URI> destinations;

    Routes(@Value("${gateway.catalog-url}") String catalog,
           @Value("${gateway.inventory-url}") String inventory,
           @Value("${gateway.order-url}") String order,
           @Value("${gateway.payment-url}") String payment) {
        destinations = Map.of("products", origin(catalog), "inventory", origin(inventory),
                "orders", origin(order), "payments", origin(payment));
    }

    static URI origin(String value) {
        URI uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))) {
            throw new IllegalArgumentException("Gateway destinations must be fixed HTTP origins");
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority());
    }

    static boolean health(String method, String path) {
        return method.equals("GET") && HEALTH.contains(path);
    }

    URI destination(String method, String path) {
        if (path.equals("/api/v1/products") && Set.of("GET", "POST").contains(method)) {
            return destinations.get("products");
        }
        if (path.equals("/api/v1/orders") && Set.of("GET", "POST").contains(method)) {
            return destinations.get("orders");
        }
        if (method.equals("GET") && (MERCHANT_ORDER.matcher(path).matches() || ORDER_HISTORY.matcher(path).matches())) {
            return destinations.get("orders");
        }
        if (method.equals("POST") && ORDER_CANCEL.matcher(path).matches()) return destinations.get("orders");
        var match = ITEM.matcher(path);
        if (!match.matches()) {
            return null;
        }
        String resource = match.group(1);
        if (method.equals("GET") || (method.equals("PUT") && Set.of("products", "inventory").contains(resource))) {
            return destinations.get(resource);
        }
        return null;
    }
}
