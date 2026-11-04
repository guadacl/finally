package com.finallyapp.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.hibernate.annotations.DynamicUpdate;

/**
 * {@code users_profile}: one row per user (id = Keycloak subject) with the cash balance and contact details.
 * {@link DynamicUpdate}: an update writes only the changed columns, so a cash change never rewrites the contact
 * details (or the other way round) with stale values.
 */
@Entity
@Table(name = "users_profile")
@DynamicUpdate
public class UserProfileEntity {

    public static final BigDecimal STARTING_CASH = new BigDecimal("10000.0000");

    @Id
    @Column(name = "id", columnDefinition = "text", nullable = false, updatable = false)
    private String id;

    @Column(name = "cash_balance", precision = 18, scale = 4, nullable = false)
    private BigDecimal cashBalance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "email", columnDefinition = "text")
    private String email;

    @Column(name = "phone", columnDefinition = "text")
    private String phone;

    protected UserProfileEntity() {
    }

    public UserProfileEntity(String id) {
        this.id = id;
        this.cashBalance = STARTING_CASH;
    }

    @PrePersist
    void onCreate() {
        if (cashBalance == null) {
            cashBalance = STARTING_CASH;
        }
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    public String getId() {
        return id;
    }

    public BigDecimal getCashBalance() {
        return cashBalance;
    }

    public void setCashBalance(BigDecimal cashBalance) {
        this.cashBalance = cashBalance;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
