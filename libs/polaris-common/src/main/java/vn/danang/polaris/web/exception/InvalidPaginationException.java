package vn.danang.polaris.web.exception;

public class InvalidPaginationException extends RuntimeException {

    private final String invalidParam;
    private final long min;
    private final long max;
    private final long received;

    public InvalidPaginationException(String invalidParam, long min, long max, long received) {
        super(formatMessage(invalidParam, min, max, received));
        this.invalidParam = invalidParam;
        this.min = min;
        this.max = max;
        this.received = received;
    }

    private static String formatMessage(String param, long min, long max, long received) {
        if ("page".equals(param)) {
            return "Page index must be between " + min + " and " + max + ". Received: " + received + ".";
        } else {
            return "Page size must be between " + min + " and " + max + ". Received: " + received + ".";
        }
    }

    public String getInvalidParam() {
        return invalidParam;
    }

    public long getMin() {
        return min;
    }

    public long getMax() {
        return max;
    }

    public long getReceived() {
        return received;
    }
}
