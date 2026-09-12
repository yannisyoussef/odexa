package commerce.inventory;

import commerce.runtime.ApiException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {
    private static final RowMapper<Stock> STOCK = (rs, row) -> new Stock(
            rs.getObject("product_id", UUID.class), rs.getLong("on_hand"), rs.getLong("reserved"),
            rs.getLong("on_hand") - rs.getLong("reserved"), rs.getLong("version"));
    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Stock get(UUID tenantId, UUID productId) {
        return find(tenantId, productId, false);
    }

    @Transactional
    public Stock adjust(UUID tenantId, UUID productId, long expectedVersion, long onHand) {
        Stock current = find(tenantId, productId, true);
        InventoryPolicy.checkAdjustment(onHand, current.reserved(), expectedVersion, current.version());
        return jdbc.query("""
                UPDATE inventory_stock SET on_hand = ?, version = version + 1
                WHERE tenant_id = ? AND product_id = ? RETURNING *
                """, STOCK, onHand, tenantId, productId).getFirst();
    }

    private Stock find(UUID tenantId, UUID productId, boolean lock) {
        return jdbc.query("SELECT * FROM inventory_stock WHERE tenant_id = ? AND product_id = ?"
                        + (lock ? " FOR UPDATE" : ""), STOCK, tenantId, productId)
                .stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "INVENTORY_NOT_FOUND", "Inventory not found"));
    }
}
