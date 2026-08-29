package com.manas.metaplatform.dsl.ast;

import java.math.BigDecimal;

/**
 * A literal value in a comparison. Sealed so every consumer (the compiler's codegen, the
 * reference interpreter, tests) is forced by the compiler to handle all three cases.
 */
public sealed interface Literal permits Literal.StringLiteral, Literal.NumberLiteral, Literal.BooleanLiteral {

    record StringLiteral(String value) implements Literal {
    }

    record NumberLiteral(BigDecimal value) implements Literal {
    }

    record BooleanLiteral(boolean value) implements Literal {
    }
}
