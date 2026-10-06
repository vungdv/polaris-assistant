package vn.danang.polaris.web.exception;

import java.util.List;

import vn.danang.polaris.web.validator.PageableValidator;

public class InvalidSortPropertyException extends RuntimeException {

    private final String invalidProperty;
    private final List<String> allowedProperties;

    public InvalidSortPropertyException(String invalidProperty) {
        this(invalidProperty, PageableValidator.ALLOWED_SORT_PROPERTIES);
    }

    public InvalidSortPropertyException(String invalidProperty, List<String> allowedProperties) {
        super(formatMessage(invalidProperty, allowedProperties));
        this.invalidProperty = invalidProperty;
        this.allowedProperties = allowedProperties;
    }

    private static String formatMessage(String property, List<String> allowed) {
        return "Invalid sort property '" + property + "'. Allowed sort properties are: " 
                + allowed + ". Format: property(,asc|desc).";
    }

    public String getInvalidProperty() {
        return invalidProperty;
    }

    public List<String> getAllowedProperties() {
        return allowedProperties;
    }
}
