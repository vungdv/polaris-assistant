package vn.danang.polaris.web.validator;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import vn.danang.polaris.web.exception.InvalidPaginationException;
import vn.danang.polaris.web.exception.InvalidSortPropertyException;

public final class PageableValidator {

    public static final int MIN_PAGE = 0;
    public static final int MAX_PAGE = 10000;
    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;

    public static final List<String> ALLOWED_SORT_PROPERTIES = List.of(
            "id", "sku", "name", "category", "price", "stockQuantity", "stockQty", "active", "createdAt"
    );

    public static final List<String> ALLOWED_ORDER_SORT_PROPERTIES = List.of(
            "id", "orderNumber", "status", "totalAmount", "placedAt", "updatedAt"
    );

    private static final Set<String> VALID_SORT_PROPERTIES = Set.of(
            "id", "sku", "name", "category", "price", "stockQuantity", "stockQty", "isActive", "active", "createdAt"
    );

    private PageableValidator() {
    }

    public static Pageable validateAndSanitize(Pageable pageable) {
        return validateAndSanitize(pageable, ALLOWED_SORT_PROPERTIES, VALID_SORT_PROPERTIES, "id", Sort.Direction.ASC);
    }

    public static Pageable validateAndSanitizeOrder(Pageable pageable) {
        return validateAndSanitize(
                pageable,
                ALLOWED_ORDER_SORT_PROPERTIES,
                Set.copyOf(ALLOWED_ORDER_SORT_PROPERTIES),
                "placedAt",
                Sort.Direction.DESC
        );
    }

    public static Pageable validateAndSanitize(
            Pageable pageable,
            List<String> allowedProperties,
            Set<String> validProperties,
            String defaultSortProperty,
            Sort.Direction defaultDirection) {
        var requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes instanceof ServletRequestAttributes servletAttrs) {
            HttpServletRequest request = servletAttrs.getRequest();
            String rawPageStr = request.getParameter("page");
            if (rawPageStr != null && !rawPageStr.isBlank()) {
                try {
                    long rawPage = Long.parseLong(rawPageStr.trim());
                    if (rawPage < MIN_PAGE || rawPage > MAX_PAGE) {
                        throw new InvalidPaginationException("page", MIN_PAGE, MAX_PAGE, rawPage);
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            String rawSizeStr = request.getParameter("size");
            if (rawSizeStr != null && !rawSizeStr.isBlank()) {
                try {
                    long rawSize = Long.parseLong(rawSizeStr.trim());
                    if (rawSize < MIN_SIZE || rawSize > MAX_SIZE) {
                        throw new InvalidPaginationException("size", MIN_SIZE, MAX_SIZE, rawSize);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(DEFAULT_PAGE, DEFAULT_SIZE, Sort.by(defaultDirection, defaultSortProperty));
        }

        int page = pageable.getPageNumber();
        if (page < MIN_PAGE || page > MAX_PAGE) {
            throw new InvalidPaginationException("page", MIN_PAGE, MAX_PAGE, page);
        }

        int size = pageable.getPageSize();
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new InvalidPaginationException("size", MIN_SIZE, MAX_SIZE, size);
        }

        Sort sort = pageable.getSort();
        if (sort.isUnsorted()) {
            return PageRequest.of(page, size, Sort.unsorted());
        }

        List<Sort.Order> sanitizedOrders = new ArrayList<>();
        for (Sort.Order order : sort) {
            String property = order.getProperty();
            if (!validProperties.contains(property)) {
                throw new InvalidSortPropertyException(property, allowedProperties);
            }

            String mappedProperty = property;
            if ("stockQuantity".equals(property)) {
                mappedProperty = "stockQty";
            } else if ("active".equals(property)) {
                mappedProperty = "isActive";
            }

            sanitizedOrders.add(new Sort.Order(order.getDirection(), mappedProperty, order.getNullHandling()));
        }

        Sort sanitizedSort = sanitizedOrders.isEmpty() ? Sort.unsorted() : Sort.by(sanitizedOrders);
        return PageRequest.of(page, size, sanitizedSort);
    }
}
