package vn.danang.polaris.catalog.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.entity.Product;

@Mapper(componentModel = "spring")
public interface ProductMapper {

    ProductMapper INSTANCE = Mappers.getMapper(ProductMapper.class);

    @Mapping(target = "stockQuantity", source = "stockQty")
    @Mapping(target = "active", source = "isActive")
    @Mapping(target = "isAvailable", expression = "java(isAvailable(product))")
    @Mapping(target = "categoryId", source = "categoryId")
    @Mapping(target = "categoryCode", source = "categoryCode")
    @Mapping(target = "category", source = "category")
    @Mapping(target = "createdAt", expression = "java(product.getCreatedAt() != null ? product.getCreatedAt() : java.time.Instant.now())")
    ProductResponse toResponse(Product product);

    List<ProductResponse> toResponseList(List<Product> products);

    @Mapping(target = "stockQty", source = "stockQuantity")
    @Mapping(target = "isActive", source = "active")
    @Mapping(target = "categoryEntity", ignore = true)
    Product toEntity(ProductResponse response);

    default boolean isAvailable(Product product) {
        return product != null
                && product.getStockQty() != null
                && product.getStockQty() > 0
                && Boolean.TRUE.equals(product.getIsActive());
    }
}
