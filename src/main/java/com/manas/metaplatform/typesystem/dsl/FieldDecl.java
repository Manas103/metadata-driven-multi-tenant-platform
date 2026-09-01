package com.manas.metaplatform.typesystem.dsl;

import com.manas.metaplatform.domain.FieldType;

/** One declared field on a {@link TypeDecl}: a name and one of the platform's three field
 * types. Reuses {@code domain.FieldType} rather than a duplicate enum, since it is the same
 * three-way distinction (STRING / NUMBER / BOOLEAN) the EAV storage already uses; the type
 * platform and the SOQL-style DSL agree on what a "field type" is even though they compile
 * to entirely different physical storage. */
public record FieldDecl(String name, FieldType type) {
}
