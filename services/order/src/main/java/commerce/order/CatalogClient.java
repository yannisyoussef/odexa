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
        CatalogProduct product;
        try {
            product = client.get().uri("/api/v1/products/{id}", request.productId())
                    .headers(headers -> {
                        headers.setBearerAuth(bearer);
                        headers.set("X-Correlation-ID", correlationId);
                    }).retrieve().body(CatalogProduct.class);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404 || failure.getStatusCode().value() == 403) {
                throw new ApiException(404, "PRODUCT_NOT_FOUND", "Product not found");
            }
            throw unavailable();
        } catch (RestClientException failure) {
            throw unavailable();
        }
        if (product == null || !request.productId().equals(product.id()) || product.active() == null) {
            throw unavailable();
        }
        if (!product.active()) {
            throw new ApiException(409, "PRODUCT_UNAVAILABLE", "Product is not available for checkout");
        }
        if (product.unitPriceMinor() == null || product.version() == null) {
            throw unavailable();
        }
        if (product.unitPriceMinor() == 0) {
            throw new ApiException(422, "PRODUCT_UNAVAILABLE", "Zero-amount checkout is not supported");
        }
        try {
            long total = Math.multiplyExact(product.unitPriceMinor(), request.quantity());
            return new CheckoutSnapshot(product.id(), request.quantity(), product.name(),
                    product.unitPriceMinor(), total, product.currency(), product.version(), request.paymentMethod());
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(503, "CATALOG_UNAVAILABLE", "Catalog is temporarily unavailable");
    }

    public record CatalogProduct(UUID id, String name, String description, Long unitPriceMinor,
                                 String currency, Boolean active, Long version) { }
}
