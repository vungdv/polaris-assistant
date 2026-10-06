package vn.danang.polaris.order.dto;

import java.math.BigDecimal;

import vn.danang.polaris.order.entity.OrderItem;

public record OrderItemResponse(
    Long id,
    String sku,
    String productName,
    Integer quantity,
    BigDecimal unitPrice,
    BigDecimal subtotal
) {
    public static OrderItemResponse from(OrderItem item) {
        BigDecimal price = item.getUnitPrice() != null ? item.getUnitPrice() : BigDecimal.ZERO;
        int qty = item.getQuantity() != null ? item.getQuantity() : 0;
        return new OrderItemResponse(
            item.getId(),
            item.getProduct() != null ? item.getProduct().getSku() : null,
            item.getProduct() != null ? item.getProduct().getName() : null,
            qty,
            price,
            price.multiply(BigDecimal.valueOf(qty))
        );
    }
}
