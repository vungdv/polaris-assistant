package vn.danang.polaris.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;

import org.mapstruct.factory.Mappers;

import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.mapper.ProductMapper;

public record ProductResponse(
    Long id,
    String sku,
    String name,
    String description,
    String category,
    BigDecimal price,
    Integer stockQuantity,
    Boolean isAvailable,
    Boolean active,
    Instant createdAt,
    Long categoryId,
    String categoryCode
) {
    private static final ProductMapper MAPPER = Mappers.getMapper(ProductMapper.class);

    public ProductResponse(
            Long id,
            String sku,
            String name,
            String description,
            String category,
            BigDecimal price,
            Integer stockQuantity,
            Boolean isAvailable,
            Boolean active,
            Instant createdAt) {
        this(id, sku, name, description, category, price, stockQuantity, isAvailable, active, createdAt, null, null);
    }

    public static ProductResponse from(Product product) {
        return MAPPER.toResponse(product);
    }
}