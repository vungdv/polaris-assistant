package vn.danang.polaris.catalog.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import vn.danang.polaris.catalog.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long>, JpaSpecificationExecutor<Category> {

    Optional<Category> findByCode(String code);

    Optional<Category> findByCodeIgnoreCase(String code);

    List<Category> findByParentId(Long parentId);

    List<Category> findByParentIdAndIsActiveTrueOrderByDisplayOrderAsc(Long parentId);

    List<Category> findByParentIsNullOrderByDisplayOrderAsc();

    List<Category> findByParentIsNullAndIsActiveTrueOrderByDisplayOrderAsc();

    List<Category> findByIsActiveTrue();

    List<Category> findByIsActiveTrueOrderByIdAsc();

    List<Category> findByIsActiveTrueOrderByDisplayOrderAsc();
}
