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

    @Column(name = "payment_requested_at")
    private LocalDateTime paymentRequestedAt;

    /**
     * payment.requested 가 재고 예약 확정(stock.reservation.result)보다 선도착했을 때의 수렴 marker.
     * confirmReservation() 시점에 PAYMENT_REQUESTED 로 수렴한다.
     */
    @Column(name = "payment_requested_pending", nullable = false)
    private boolean paymentRequestedPending;

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
        this.paymentRequestedPending = false;
    }

    /**
     * 재고 예약 확정을 기록한다 (stock.reservation.result reserved=true).
     * 예약 미확정 PENDING 주문의 타임아웃 수렴에서 조기 취소를 막는 표식이다.
     * payment.requested 가 선도착해 pending marker 가 켜져 있으면 여기서 PAYMENT_REQUESTED 로 수렴한다.
     */
    public void confirmReservation() {
        this.reservationConfirmedAt = LocalDateTime.now();
        if (this.paymentRequestedPending && this.status == OrderStatus.PENDING) {
            this.paymentRequestedPending = false;
            markPaymentRequested();
        }
    }

    /**
     * payment.requested 선도착(예약 미확정) 시 수렴 marker 를 기록한다 (ORD-008 DLQ 직행 방지).
     * 종료 상태 주문에는 기록하지 않는다.
     */
    public void markPaymentRequestedPending() {
        if (this.status == OrderStatus.PENDING) {
            this.paymentRequestedPending = true;
        }
    }

    /**
     * 결제 승인을 요청한다. 재고 예약이 확정된 주문만 결제로 진입할 수 있다 (strangler-3 게이트, ADR-0012 ①).
     * <p>전이 불가(취소/종결) 는 영구 실패({@code ORD-003}), 예약 미확정(in-flight) 은 재시도 가능({@code ORD-008}) 으로 구분한다.
     * 전이 시각({@code paymentRequestedAt}) 을 기록해 타임아웃 기준이 주문 생성이 아닌 결제 요청 시점이 되도록 한다.
     *
     * @throws OrderException 전이 불가 상태면 {@code ORD-003}, 예약 미확정이면 {@code ORD-008}
     */
    public void markPaymentRequested() {
        if (!this.status.canTransitionTo(OrderStatus.PAYMENT_REQUESTED)) {
            throw new OrderException(ErrorCode.ORD_003);
        }
        if (this.reservationConfirmedAt == null) {
            throw new OrderException(ErrorCode.ORD_008);
        }
        this.status = OrderStatus.PAYMENT_REQUESTED;
        this.paymentRequestedAt = LocalDateTime.now();
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
