package commerce.paymentsimulator;

final class ProviderProblem extends RuntimeException {
    private final int status;
    private final String code;
    ProviderProblem(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    int status() { return status; }
    String code() { return code; }
}
