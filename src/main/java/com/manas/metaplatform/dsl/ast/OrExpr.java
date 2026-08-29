package com.manas.metaplatform.dsl.ast;

public record OrExpr(Expr left, Expr right) implements Expr {
}
