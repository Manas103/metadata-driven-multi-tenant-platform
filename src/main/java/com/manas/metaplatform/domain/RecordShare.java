package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An explicit share grant: one record, made visible to one user who is not necessarily its
 * owner and not necessarily anywhere in the owner's role-hierarchy chain. This is the third
 * and last way a record can become visible to a viewer (ownership, role hierarchy, or an
 * explicit grant here); {@code compiler.SqlCompiler} lowers a check against this table
 * directly as {@code EXISTS (SELECT 1 FROM record_shares ...)} inside the sharing predicate
 * it generates for {@link com.manas.metaplatform.dsl.ast.SharingScopeNode}.
 */
@Entity
@Table(name = "record_shares")
public class RecordShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "shared_with_user_id", nullable = false)
    private Long sharedWithUserId;

    protected RecordShare() {
    }

    public RecordShare(Long recordId, Long sharedWithUserId) {
        this.recordId = recordId;
        this.sharedWithUserId = sharedWithUserId;
    }

    public Long getId() {
        return id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public Long getSharedWithUserId() {
        return sharedWithUserId;
    }
}
