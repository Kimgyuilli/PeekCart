package com.peekcart.product.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.domain.exception.ProductException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 재고 엔티티. 낙관적 락({@code @Version})으로 동시성을 제어한다.
 */
@Entity
@Table(name = "inventories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(nullable = false)
    private int stock;

    @Version
    private int version;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private Inventory(Product product, int stock) {
        this.product = product;
        this.stock = stock;
    }

    public static Inventory create(Product product, int stock) {
        return new Inventory(product, stock);
    }

    /**
     * 재고를 차감한다. 재고 부족 시 {@link ProductException}(PRD-002)을 던진다.
     */
    public void decrease(int quantity) {
        if (this.stock < quantity) {
            throw new ProductException(ErrorCode.PRD_002);
        }
        this.stock -= quantity;
    }

    /**
     * 재고를 복구한다.
     */
    public void restore(int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("복구 수량은 1 이상이어야 합니다.");
        this.stock += quantity;
    }
}
