package vn.danang.polaris.web.exception;

public class DraftExpiredException extends RuntimeException {

    private final String draftId;

    public DraftExpiredException(String draftId, String message) {
        super(message);
        this.draftId = draftId;
    }

    public String getDraftId() {
        return draftId;
    }
}
