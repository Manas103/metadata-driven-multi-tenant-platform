package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One field's value on one record: a sparse EAV row. A record with no value set for a
 * given field simply has no row here for that (record, field) pair, which is what lets a
 * tenant add a field to an object with millions of existing records without touching any
 * of them. Exactly one of {@code stringValue}, {@code numberValue}, {@code booleanValue}
 * is populated, chosen by the owning {@link FieldDefinition#getFieldType()}.
 */
@Entity
@Table(name = "field_values")
public class FieldValueEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "field_definition_id", nullable = false)
    private Long fieldDefinitionId;

    @Column(name = "string_value", length = 4000)
    private String stringValue;

    @Column(name = "number_value", precision = 20, scale = 6)
    private BigDecimal numberValue;

    @Column(name = "boolean_value")
    private Boolean booleanValue;

    protected FieldValueEntity() {
    }

    private FieldValueEntity(Long recordId, Long fieldDefinitionId) {
        this.recordId = recordId;
        this.fieldDefinitionId = fieldDefinitionId;
    }

    public static FieldValueEntity ofString(Long recordId, Long fieldDefinitionId, String value) {
        FieldValueEntity v = new FieldValueEntity(recordId, fieldDefinitionId);
        v.stringValue = value;
        return v;
    }

    public static FieldValueEntity ofNumber(Long recordId, Long fieldDefinitionId, BigDecimal value) {
        FieldValueEntity v = new FieldValueEntity(recordId, fieldDefinitionId);
        v.numberValue = value;
        return v;
    }

    public static FieldValueEntity ofBoolean(Long recordId, Long fieldDefinitionId, Boolean value) {
        FieldValueEntity v = new FieldValueEntity(recordId, fieldDefinitionId);
        v.booleanValue = value;
        return v;
    }

    public Long getId() {
        return id;
    }

    public Long getRecordId() {
        return recordId;
    }

    public Long getFieldDefinitionId() {
        return fieldDefinitionId;
    }

    public String getStringValue() {
        return stringValue;
    }

    public BigDecimal getNumberValue() {
        return numberValue;
    }

    public Boolean getBooleanValue() {
        return booleanValue;
    }
}
