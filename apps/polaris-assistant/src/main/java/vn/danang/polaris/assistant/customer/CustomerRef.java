package vn.danang.polaris.assistant.customer;

import java.util.Objects;

import jakarta.annotation.Nullable;

/**
 * The customer an order draft is for, as Order Management reports it.
 *
 * @param id       customer id
 * @param fullName display name, or null when it could not be obtained cheaply
 */
public record CustomerRef(Long id, @Nullable String fullName) {

    public CustomerRef {
        Objects.requireNonNull(id, "id must not be null");
    }
}
