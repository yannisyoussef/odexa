package commerce.order;

import commerce.runtime.ApiException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class CatalogClient {
    private final RestClient client;

    @Autowired
    public CatalogClient(@Value("${order.catalog.url}") String baseUrl,
                         @Value("${order.catalog.connect-timeout:2s}") Duration connectTimeout,
                         @Value("${order.catalog.read-timeout:3s}") Duration readTimeout) {
        this(createClient(baseUrl, connectTimeout, readTimeout));
    }

    CatalogClient(RestClient client) {
        this.client = client;
    }

    private static RestClient createClient(String baseUrl, Duration connect, Duration read) {
        if (connect.isZero() || connect.isNegative() || connect.compareTo(Duration.ofSeconds(10)) > 0
                || read.isZero() || read.isNegative() || read.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalArgumentException("Catalog timeouts must be positive and at most 10 seconds");
        }
        HttpClient http = HttpClient.newBuilder().connectTimeout(connect)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(read);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public CheckoutSnapshot snapshot(CheckoutRequest request, String bearer, String correlationId) {
        var intent = request.normalizedItems();
        CatalogProduct[] products;
        try {
            products = client.post().uri("/api/internal/v1/products/batch")
                    .headers(headers -> {
                        headers.setBearerAuth(bearer);
                        headers.set("X-Correlation-ID", correlationId);
                    }).body(java.util.Map.of("productIds", intent.stream().map(CheckoutRequest.Item::productId).toList()))
                    .retrieve().body(CatalogProduct[].class);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404 || failure.getStatusCode().value() == 403)
                throw new ApiException(404, "PRODUCT_NOT_FOUND", "Product not found");
            throw unavailable();
        } catch (RestClientException failure) { throw unavailable(); }
        if (products == null || products.length != intent.size()) throw unavailable();
        java.util.Map<UUID, CatalogProduct> byId = new java.util.HashMap<>();
        for (CatalogProduct product : products) {
            if (product == null || product.id() == null || product.active() == null || product.unitPriceMinor() == null
                    || product.version() == null || byId.put(product.id(), product) != null) throw unavailable();
        }
        var lines = new java.util.ArrayList<OrderLine>();
        long total = 0;
        try {
            for (var item : intent) {
                CatalogProduct product = byId.get(item.productId());
                if (product == null) throw unavailable();
                if (!product.active()) throw new ApiException(409, "PRODUCT_UNAVAILABLE", "Product is not available for checkout");
                if (!"USD".equals(product.currency())) throw unavailable();
                long lineTotal = Math.multiplyExact(product.unitPriceMinor(), item.quantity());
                lines.add(new OrderLine(product.id(), item.quantity(), product.name(), product.unitPriceMinor(), lineTotal, product.version()));
                total = Math.addExact(total, lineTotal);
            }
            if (total == 0) throw new ApiException(422, "PRODUCT_UNAVAILABLE", "Zero-amount checkout is not supported");
            return new CheckoutSnapshot(lines, total, "USD", request.paymentMethod());
        } catch (ArithmeticException overflow) {
            throw new ApiException(422, "ORDER_TOTAL_OVERFLOW", "Checkout total exceeds the supported amount");
        } catch (IllegalArgumentException failure) { throw unavailable(); }
    }

    private static ApiException unavailable() {
        return new ApiException(503, "CATALOG_UNAVAILABLE", "Catalog is temporarily unavailable");
    }

    public record CatalogProduct(UUID id, String name, String description, Long unitPriceMinor,
                                 String currency, Boolean active, Long version) { }
}
