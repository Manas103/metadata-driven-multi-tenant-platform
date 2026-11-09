package com.manas.metaplatform.bookofrecord.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A simulated fund account, tenant-scoped so the book of record sits inside the same
 * multi-tenant platform the rest of this repository proves isolation for. Nothing about
 * this entity re-runs the tenant-isolation suite; it inherits that claim from the
 * EAV/DSL subsystem's own 500-probe result, unchanged by this extension.
 */
@Entity
@Table(name = "fund_accounts")
public class FundAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "account_name", nullable = false, length = 200)
    private String accountName;

    protected FundAccount() {
    }

    public FundAccount(Long tenantId, String accountName) {
        this.tenantId = tenantId;
        this.accountName = accountName;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getAccountName() {
        return accountName;
    }
}
