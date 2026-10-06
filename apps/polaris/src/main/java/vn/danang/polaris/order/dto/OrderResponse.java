package vn.danang.polaris.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import vn.danang.polaris.order.entity.Order;
import vn.danang.polaris.order.entity.OrderStatus;

public record OrderResponse(
    Long id,
    String orderNumber,
    OrderStatus status,
    BigDecimal totalAmount,
    Instant placedAt,
    Instant updatedAt,
    String customerName,
    String assignedPartner,
    List<OrderItemResponse> items
) {
    public static OrderResponse from(Order order) {
        List<OrderItemResponse> itemResponses = order.getItems() != null
            ? order.getItems().stream().map(OrderItemResponse::from).toList()
            : List.of();
        String custName = order.getCustomer() != null ? order.getCustomer().getFullName() : null;
        return new OrderResponse(
            order.getId(),
            order.getOrderNumber(),
            order.getStatus(),
            order.getTotalAmount(),
            order.getPlacedAt(),
            order.getUpdatedAt(),
            custName,
            order.getAssignedPartner(),
            itemResponses
        );
    }
}
