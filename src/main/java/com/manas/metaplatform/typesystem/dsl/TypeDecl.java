package com.manas.metaplatform.typesystem.dsl;

import java.util.List;

/** One declared entity type: a name and its ordered, typed fields. This is the single
 * declaration every downstream artifact (DDL, REST surface, Java client, TypeScript client)
 * is generated from; nothing downstream re-specifies a field independently. */
public record TypeDecl(String name, List<FieldDecl> fields) {
}
