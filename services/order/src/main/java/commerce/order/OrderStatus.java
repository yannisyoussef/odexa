package commerce.order;

public enum OrderStatus {
    CREATED, PENDING_PAYMENT, CONFIRMED, STOCK_REJECTED, PAYMENT_FAILED, CANCELLED, EXPIRED;

    public boolean terminal() {
        return this == CONFIRMED || this == STOCK_REJECTED || this == PAYMENT_FAILED || this == CANCELLED || this == EXPIRED;
    }
}
