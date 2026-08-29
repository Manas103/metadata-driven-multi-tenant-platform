package com.manas.metaplatform.dsl.ast;

/** A leaf predicate: {@code fieldName operator literal}, e.g. {@code age > 5}. */
public record Comparison(String fieldName, Operator operator, Literal literal) implements Expr {
}
