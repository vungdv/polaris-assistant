package vn.danang.polaris.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;

import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import vn.danang.polaris.catalog.dto.CreateProductRequest;
import vn.danang.polaris.catalog.dto.ProductResponse;
import vn.danang.polaris.catalog.entity.Category;
import vn.danang.polaris.catalog.entity.Product;
import vn.danang.polaris.catalog.mapper.ProductMapper;
import vn.danang.polaris.catalog.repository.CategoryRepository;
import vn.danang.polaris.catalog.repository.ProductRepository;
import vn.danang.polaris.catalog.repository.ProductSpecifications;
import vn.danang.polaris.config.CacheConfig;
import vn.danang.polaris.web.exception.DuplicateSkuException;
import vn.danang.polaris.web.exception.ResourceNotFoundException;

@Service
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;

    @Autowired
    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository, ProductMapper productMapper) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.productMapper = productMapper;
    }

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this(productRepository, categoryRepository, Mappers.getMapper(ProductMapper.class));
    }

    public ProductService(ProductRepository productRepository) {
        this(productRepository, null);
    }

    public Page<ProductResponse> searchProducts(
            String query,
            String category,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean available,
            Pageable pageable) {
        return searchProducts(query, category, null, minPrice, maxPrice, available, pageable);
    }

    public Page<ProductResponse> searchProducts(
            String query,
            String category,
            Long categoryId,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Boolean available,
            Pageable pageable) {

        Specification<Product> spec = Specification
                .where(ProductSpecifications.hasKeyword(query))
                .and(ProductSpecifications.hasCategory(category))
                .and(ProductSpecifications.hasCategoryId(categoryId))
                .and(ProductSpecifications.minPrice(minPrice))
                .and(ProductSpecifications.maxPrice(maxPrice))
                .and(ProductSpecifications.isAvailable(available));

        return productRepository.findAll(spec, pageable)
                .map(productMapper::toResponse);
    }

    @Cacheable(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#id")
    public ProductResponse getProductById(Long id) {
        return productRepository.findById(id)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
    }

    // SKU lookups share the "products" cache with id lookups; see CacheConfig.productSkuKey for the key format.
    @Cacheable(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "T(vn.danang.polaris.config.CacheConfig).productSkuKey(#sku)")
    public ProductResponse getProductBySku(String sku) {
        return productRepository.findBySkuIgnoreCase(sku)
                .map(productMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
    }

    // Primes the cache with the newly created product (by id and by SKU) instead of evicting,
    // since there's nothing stale to invalidate for a brand-new id - this just saves the very
    // next getProductById/getProductBySku call (a common create-then-fetch pattern) a round trip.
    @Transactional
    @Caching(put = {
            @CachePut(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#result.id()"),
            @CachePut(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "T(vn.danang.polaris.config.CacheConfig).productSkuKey(#result.sku())")
    })
    public ProductResponse createProduct(CreateProductRequest request) {
        String trimmedSku = request.sku() != null ? request.sku().trim() : "";
        if (productRepository.existsBySkuIgnoreCase(trimmedSku)) {
            throw new DuplicateSkuException(trimmedSku);
        }

        Category categoryEntity = null;
        if (request.categoryId() != null) {
            if (categoryRepository == null) {
                throw new IllegalStateException("CategoryRepository is required to associate categories.");
            }
            categoryEntity = categoryRepository.findById(request.categoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found with id: " + request.categoryId()));
        }

        int stockQty = request.stockQuantity() != null ? request.stockQuantity() : 0;
        if (stockQty < 0) {
            throw new IllegalArgumentException("Stock quantity cannot be negative: " + stockQty);
        }

        Product product = new Product();
        product.setSku(trimmedSku);
        product.setName(request.name() != null ? request.name().trim() : null);
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setStockQty(stockQty);
        product.setIsActive(request.active() != null ? request.active() : true);
        product.setCreatedAt(Instant.now());

        if (categoryEntity != null) {
            product.setCategoryEntity(categoryEntity);
            product.setCategory(categoryEntity.getName());
        } else if (request.category() != null) {
            product.setCategory(request.category().trim());
        }

        Product saved = productRepository.save(product);
        return productMapper.toResponse(saved);
    }

    // @CacheEvict rather than @CachePut: this returns the Product entity, not the ProductResponse
    // the "products" cache stores, so putting #result straight back in would poison the cache
    // with the wrong type. Evicting both keys (id and sku) forces the next read of either to
    // repopulate from the DB with a correctly mapped ProductResponse.
    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "T(vn.danang.polaris.config.CacheConfig).productSkuKey(#result.getSku())")
    })
    public Product adjustInventoryById(Long id, int delta) {
        // Pessimistic write lock (SELECT ... FOR UPDATE via LockModeType.PESSIMISTIC_WRITE)
        // prevents race conditions and lost updates during concurrent delta adjustments (ADR-0007).
        Product product = productRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + id));
        int current = product.getStockQty();
        int target = current + delta;
        if (target < 0) {
            throw new IllegalArgumentException("Cannot adjust stock below 0. Current: " + current + ", delta: " + delta);
        }
        product.setStockQty(target);
        return productRepository.save(product);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#result.getId()"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "T(vn.danang.polaris.config.CacheConfig).productSkuKey(#sku)")
    })
    public Product adjustInventory(String sku, int delta) {
        // Pessimistic write lock (SELECT ... FOR UPDATE via LockModeType.PESSIMISTIC_WRITE)
        // prevents race conditions and lost updates during concurrent delta adjustments (ADR-0007).
        Product product = productRepository.findBySkuIgnoreCaseForUpdate(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        int current = product.getStockQty() != null ? product.getStockQty() : 0;
        int target = current + delta;
        if (target < 0) {
            throw new IllegalArgumentException("Cannot adjust stock below 0. Current: " + current + ", delta: " + delta);
        }
        product.setStockQty(target);
        return productRepository.save(product);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#result.getId()"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "T(vn.danang.polaris.config.CacheConfig).productSkuKey(#sku)")
    })
    public Product deductStock(String sku, int quantity) {
        Product product = productRepository.findBySkuIgnoreCaseForUpdate(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        int currentStock = product.getStockQty() != null ? product.getStockQty() : 0;
        if (currentStock < quantity) {
            throw new vn.danang.polaris.web.exception.InsufficientStockException(product.getSku(), quantity, currentStock);
        }
        product.setStockQty(currentStock - quantity);
        return productRepository.save(product);
    }

    @Transactional
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "#result.getId()"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS_CACHE, key = "T(vn.danang.polaris.config.CacheConfig).productSkuKey(#sku)")
    })
    public Product restoreStock(String sku, int quantity) {
        Product product = productRepository.findBySkuIgnoreCaseForUpdate(sku)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with SKU: " + sku));
        int currentStock = product.getStockQty() != null ? product.getStockQty() : 0;
        product.setStockQty(currentStock + quantity);
        return productRepository.save(product);
    }
}
