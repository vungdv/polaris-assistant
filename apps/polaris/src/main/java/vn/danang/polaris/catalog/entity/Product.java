// domain/Product.java
package vn.danang.polaris.catalog.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter @Setter
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String sku;
    private String name;
    private String description;
    private String category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category categoryEntity;

    private BigDecimal price;
    private Integer stockQty;
    private Boolean isActive = true;
    private Instant createdAt;

    public String getCategory() {
        if (categoryEntity != null && categoryEntity.getName() != null) {
            return categoryEntity.getName();
        }
        return this.category;
    }

    public Long getCategoryId() {
        return categoryEntity != null ? categoryEntity.getId() : null;
    }

    public String getCategoryCode() {
        return categoryEntity != null ? categoryEntity.getCode() : null;
    }
}