package com.manas.metaplatform.dsl.ast;

public record AndExpr(Expr left, Expr right) implements Expr {
}
