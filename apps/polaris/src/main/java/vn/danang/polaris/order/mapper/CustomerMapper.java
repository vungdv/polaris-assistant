package vn.danang.polaris.order.mapper;

import java.util.List;

import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;
import org.mapstruct.factory.Mappers;

import vn.danang.polaris.order.dto.CustomerResponse;
import vn.danang.polaris.order.dto.UpdateCustomerRequest;
import vn.danang.polaris.order.entity.Customer;

/**
 * MapStruct mapper between {@link Customer} entity and {@link CustomerResponse} DTO.
 * <p>
 * Demonstrates MapStruct's most compelling and useful capability:
 * when the target DTO properties match the source entity properties identically in name and type,
 * MapStruct automatically maps every property at compile time without requiring any manual
 * {@code @Mapping} annotations or reflective runtime overhead.
 */
@Mapper(componentModel = "spring")
public interface CustomerMapper {

    CustomerMapper INSTANCE = Mappers.getMapper(CustomerMapper.class);

    CustomerResponse toResponse(Customer customer);

    List<CustomerResponse> toResponseList(List<Customer> customers);

    @Mapping(target = "authSubject", ignore = true)
    Customer toEntity(CustomerResponse response);

    // These fields should be handled by the repository layer.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "version", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    // The account link is owned by identity resolution, never by a profile update.
    @Mapping(target = "authSubject", ignore = true)
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void updateCustomerFromRequest(UpdateCustomerRequest request, @MappingTarget Customer customer);
}
