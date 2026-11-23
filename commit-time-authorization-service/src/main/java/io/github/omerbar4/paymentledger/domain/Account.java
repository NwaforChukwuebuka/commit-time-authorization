package io.github.omerbar4.paymentledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "allow_negative_balance", nullable = false)
    private boolean allowNegativeBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String name, String currency, boolean allowNegativeBalance) {
        this.name = name;
        this.currency = currency;
        this.allowNegativeBalance = allowNegativeBalance;
        this.status = AccountStatus.ACTIVE;
        this.balance = Money.normalize(BigDecimal.ZERO);
        this.createdAt = Instant.now();
    }

    public boolean canDebit(BigDecimal amount) {
        return allowNegativeBalance || balance.compareTo(amount) >= 0;
    }

    /** Opening balance for a seeded account. Not a payment and not a commit-time posting. */
    public void seedBalance(BigDecimal opening) {
        if (balance.compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalStateException("Account " + id + " already has a balance");
        }
        this.balance = Money.normalize(opening);
    }

    public void freeze() {
        this.status = AccountStatus.FROZEN;
    }

    void debit(BigDecimal amount) {
        balance = balance.subtract(amount);
    }

    void credit(BigDecimal amount) {
        balance = balance.add(amount);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isAllowNegativeBalance() {
        return allowNegativeBalance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
