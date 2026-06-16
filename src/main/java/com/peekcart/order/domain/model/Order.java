package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 애그리거트 루트. 상태 전이 로직을 직접 보유한다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_number", nullable = false, unique = true)
    private String orderNumber;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "receiver_name", nullable = false)
    private String receiverName;

    @Column(nullable = false)
    private String phone;

    @Column(nullable = false)
    private String zipcode;

    @Column(nullable = false)
    private String address;

    @Column(name = "ordered_at", nullable = false)
    private LocalDateTime orderedAt;

    @Column(name = "reservation_confirmed_at")
    private LocalDateTime reservationConfirmedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> orderItems = new ArrayList<>();

    private Order(Long userId, String orderNumber, String receiverName, String phone,
                  String zipcode, String address, List<OrderItemData> itemDataList) {
        if (itemDataList == null || itemDataList.isEmpty()) {
            throw new OrderException(ErrorCode.ORD_004);
        }
        this.userId = userId;
        this.orderNumber = orderNumber;
        this.status = OrderStatus.PENDING;
        this.receiverName = receiverName;
        this.phone = phone;
        this.zipcode = zipcode;
        this.address = address;
        this.orderedAt = LocalDateTime.now();

        for (OrderItemData data : itemDataList) {
            this.orderItems.add(new OrderItem(this, data.productId(), data.quantity(), data.unitPrice()));
        }
        this.totalAmount = this.orderItems.stream().mapToLong(OrderItem::getSubtotal).sum();
    }

    public static Order create(Long userId, String orderNumber, String receiverName, String phone,
                               String zipcode, String address, List<OrderItemData> itemDataList) {
        return new Order(userId, orderNumber, receiverName, phone, zipcode, address, itemDataList);
    }

    /**
     * 주문을 취소한다.
     *
     * @throws OrderException 이미 취소된 주문이면 {@code ORD-002}, 취소 불가 상태면 {@code ORD-003}
     */
    public void cancel() {
        if (this.status == OrderStatus.CANCELLED) {
            throw new OrderException(ErrorCode.ORD_002);
        }
        if (!this.status.canTransitionTo(OrderStatus.CANCELLED)) {
            throw new OrderException(ErrorCode.ORD_003);
        }
        this.status = OrderStatus.CANCELLED;
    }

    /**
     * 재고 예약 확정을 기록한다 (stock.reservation.result reserved=true).
     * 예약 미확정 PENDING 주문의 타임아웃 수렴에서 조기 취소를 막는 표식이다.
     */
    public void confirmReservation() {
        this.reservationConfirmedAt = LocalDateTime.now();
    }

    /**
     * 상태를 전이한다.
     *
     * @throws OrderException 허용되지 않은 전이면 {@code ORD-003}
     */
    public void transitionTo(OrderStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new OrderException(ErrorCode.ORD_003);
        }
        this.status = target;
    }
}
