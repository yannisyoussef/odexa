package commerce.order;

public enum OrderStatus {
    CREATED, PENDING_PAYMENT, CONFIRMED, STOCK_REJECTED, PAYMENT_FAILED;

    public boolean terminal() {
        return this == CONFIRMED || this == STOCK_REJECTED || this == PAYMENT_FAILED;
    }
}
