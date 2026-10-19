package com.ledgerline.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType type;

    // The column is CHAR(3) in PostgreSQL; this tells Hibernate to expect CHAR, not VARCHAR.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    // Minor units (cents). Never a float or double.
    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // Required by JPA. Not for application code.
    protected Account() {
    }

    private Account(String name, AccountType type, String currency) {
        this.name = name;
        this.type = type;
        this.currency = currency;
        this.balance = 0L;
        this.createdAt = Instant.now();
    }

    /** The only way the API creates accounts: CUSTOMER type, zero balance. */
    public static Account newCustomerAccount(String name, String currency) {
        return new Account(name, AccountType.CUSTOMER, currency);
    }

    // Getters only. Balance changes will go through transfers (Day 2), never a public setter.
    public UUID getId() { return id; }
    public String getName() { return name; }
    public AccountType getType() { return type; }
    public String getCurrency() { return currency; }
    public long getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }
}