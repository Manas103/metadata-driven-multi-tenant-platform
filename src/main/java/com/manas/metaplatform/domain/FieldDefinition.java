package com.manas.metaplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A tenant-defined field on an {@link ObjectDefinition}: a name plus a {@link FieldType}.
 * Declaring a new field is one row here, never an {@code ALTER TABLE}. Every field, no
 * matter how many a tenant declares, is stored in the same three typed columns on
 * {@link FieldValueEntity}, keyed by this row's id.
 */
@Entity
@Table(name = "field_definitions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"object_definition_id", "name"}))
public class FieldDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "object_definition_id", nullable = false)
    private Long objectDefinitionId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "field_type", nullable = false, length = 20)
    private FieldType fieldType;

    protected FieldDefinition() {
    }

    public FieldDefinition(Long objectDefinitionId, String name, FieldType fieldType) {
        this.objectDefinitionId = objectDefinitionId;
        this.name = name;
        this.fieldType = fieldType;
    }

    public Long getId() {
        return id;
    }

    public Long getObjectDefinitionId() {
        return objectDefinitionId;
    }

    public String getName() {
        return name;
    }

    public FieldType getFieldType() {
        return fieldType;
    }
}
