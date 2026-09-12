package commerce.catalog;

import commerce.runtime.ApiException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogService {
    private static final RowMapper<Product> PRODUCT = (rs, row) -> new Product(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
            rs.getLong("unit_price_minor"), rs.getString("currency"), rs.getBoolean("active"),
            rs.getLong("version"));
    private final JdbcTemplate jdbc;

    public CatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Product get(UUID tenantId, UUID id) {
        return jdbc.query("SELECT * FROM product WHERE tenant_id = ? AND id = ?", PRODUCT, tenantId, id)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "PRODUCT_NOT_FOUND", "Product not found"));
    }

    public ProductPage list(UUID tenantId, int limit, String cursor, String query) {
        if (limit < 1 || limit > 100 || (query != null && query.length() > 200)) {
            throw new ApiException(400, "INVALID_QUERY", "Limit must be 1..100 and search at most 200 characters");
        }
        UUID after = CatalogPolicy.decodeCursor(cursor);
        String pattern = CatalogPolicy.searchPattern(query);
        String sql = "SELECT * FROM product WHERE tenant_id = ? AND (name ILIKE ? OR description ILIKE ?)";
        List<Product> rows = after == null
                ? jdbc.query(sql + " ORDER BY id LIMIT ?", PRODUCT, tenantId, pattern, pattern, limit + 1)
                : jdbc.query(sql + " AND id > ? ORDER BY id LIMIT ?", PRODUCT, tenantId, pattern, pattern, after, limit + 1);
        boolean more = rows.size() > limit;
        List<Product> items = more ? rows.subList(0, limit) : rows;
        return new ProductPage(items, more ? CatalogPolicy.encodeCursor(items.getLast().id()) : null);
    }

    @Transactional
    public Product create(UUID tenantId, ProductInput input) {
        UUID id = UUID.randomUUID();
        return jdbc.query("""
                INSERT INTO product (tenant_id, id, name, description, unit_price_minor, currency, active)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, PRODUCT, tenantId, id, input.name(), input.description(), input.unitPriceMinor(),
                input.currency(), input.active()).getFirst();
    }

    @Transactional
    public Product update(UUID tenantId, UUID id, long expectedVersion, ProductInput input) {
        List<Product> updated = jdbc.query("""
                UPDATE product SET name = ?, description = ?, unit_price_minor = ?, currency = ?,
                    active = ?, version = version + 1
                WHERE tenant_id = ? AND id = ? AND version = ? RETURNING *
                """, PRODUCT, input.name(), input.description(), input.unitPriceMinor(), input.currency(),
                input.active(), tenantId, id, expectedVersion);
        if (updated.isEmpty()) {
            get(tenantId, id);
            throw new ApiException(412, "STALE_VERSION", "The product version has changed");
        }
        return updated.getFirst();
    }
}
