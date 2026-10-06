package vn.danang.polaris.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import vn.danang.polaris.catalog.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    Optional<Product> findBySku(String sku);
    Optional<Product> findBySkuIgnoreCase(String sku);
    boolean existsBySkuIgnoreCase(String sku);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE LOWER(p.sku) = LOWER(:sku)")
    Optional<Product> findBySkuIgnoreCaseForUpdate(@Param("sku") String sku);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT p.categoryEntity.id, COUNT(p) FROM Product p WHERE p.categoryEntity IS NOT NULL GROUP BY p.categoryEntity.id")
    List<Object[]> countProductsGroupedByCategoryId();

    @Query("SELECT COUNT(p) FROM Product p WHERE p.categoryEntity.id = :categoryId OR p.categoryEntity.parent.id = :categoryId")
    long countByCategoryIdOrParentCategoryId(@Param("categoryId") Long categoryId);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Product p SET p.stockQty = p.stockQty - :quantity WHERE p.id = :productId AND p.stockQty >= :quantity")
    int decrementStockIfAvailable(@Param("productId") Long productId, @Param("quantity") int quantity);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE Product p SET p.stockQty = p.stockQty + :quantity WHERE p.id = :productId")
    int incrementStock(@Param("productId") Long productId, @Param("quantity") int quantity);
}