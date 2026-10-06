package vn.danang.polaris.catalog.repository;

import java.math.BigDecimal;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

import vn.danang.polaris.catalog.entity.Category;
import vn.danang.polaris.catalog.entity.Product;

public final class ProductSpecifications {

    private ProductSpecifications() {}

    public static Specification<Product> hasKeyword(String query) {
        return (root, cq, cb) -> {
            if (query == null || query.isBlank()) {
                return cb.conjunction();
            }
            String pattern = "%" + query.trim().toLowerCase() + "%";
            return cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("sku")), pattern),
                cb.like(cb.lower(root.get("description")), pattern)
            );
        };
    }

    public static Specification<Product> hasCategory(String category) {
        return (root, cq, cb) -> {
            if (category == null || category.isBlank()) {
                return cb.conjunction();
            }
            String target = category.trim().toLowerCase();
            Join<Product, Category> categoryJoin = root.join("categoryEntity", JoinType.LEFT);
            Join<Category, Category> parentJoin = categoryJoin.join("parent", JoinType.LEFT);

            return cb.or(
                cb.equal(cb.lower(root.get("category")), target),
                cb.equal(cb.lower(categoryJoin.get("code")), target),
                cb.equal(cb.lower(categoryJoin.get("name")), target),
                cb.equal(cb.lower(parentJoin.get("code")), target),
                cb.equal(cb.lower(parentJoin.get("name")), target)
            );
        };
    }

    public static Specification<Product> hasCategoryId(Long categoryId) {
        return (root, cq, cb) -> {
            if (categoryId == null) {
                return cb.conjunction();
            }
            Join<Product, Category> categoryJoin = root.join("categoryEntity", JoinType.LEFT);
            Join<Category, Category> parentJoin = categoryJoin.join("parent", JoinType.LEFT);

            return cb.or(
                cb.equal(categoryJoin.get("id"), categoryId),
                cb.equal(parentJoin.get("id"), categoryId)
            );
        };
    }

    public static Specification<Product> minPrice(BigDecimal minPrice) {
        return (root, cq, cb) -> {
            if (minPrice == null) {
                return cb.conjunction();
            }
            return cb.greaterThanOrEqualTo(root.get("price"), minPrice);
        };
    }

    public static Specification<Product> maxPrice(BigDecimal maxPrice) {
        return (root, cq, cb) -> {
            if (maxPrice == null) {
                return cb.conjunction();
            }
            return cb.lessThanOrEqualTo(root.get("price"), maxPrice);
        };
    }

    public static Specification<Product> isAvailable(Boolean available) {
        return (root, cq, cb) -> {
            if (available == null) {
                return cb.conjunction();
            }
            if (Boolean.TRUE.equals(available)) {
                return cb.and(
                    cb.greaterThan(root.get("stockQty"), 0),
                    cb.isTrue(root.get("isActive"))
                );
            } else {
                return cb.or(
                    cb.lessThanOrEqualTo(root.get("stockQty"), 0),
                    cb.isFalse(root.get("isActive"))
                );
            }
        };
    }
}
