package vn.danang.polaris.web.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class DuplicateResourceException extends RuntimeException {

    private final String resourceType;
    private final String identifier;

    public DuplicateResourceException(String message) {
        super(message);
        this.resourceType = null;
        this.identifier = null;
    }

    public DuplicateResourceException(String resourceType, String identifier, String message) {
        super(message);
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getIdentifier() {
        return identifier;
    }
}
