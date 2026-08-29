package com.manas.metaplatform.dsl;

import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Comparison;
import com.manas.metaplatform.dsl.ast.Literal;
import com.manas.metaplatform.dsl.ast.Operator;
import com.manas.metaplatform.dsl.ast.OrExpr;
import com.manas.metaplatform.dsl.ast.SelectQuery;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the hand-rolled lexer and recursive-descent parser: grammar coverage
 * (select list, WHERE shapes, parenthesization, operators, literal kinds) and the syntax
 * errors the lexer/parser are supposed to raise rather than silently accept.
 */
class ParserTest {

    @Test
    void parsesSelectWithNoWhere() {
        SelectQuery q = Parser.parse("SELECT name, age FROM Contact").query();
        assertThat(q.selectFields()).containsExactly("name", "age");
        assertThat(q.objectName()).isEqualTo("Contact");
        assertThat(q.where()).isEmpty();
    }

    @Test
    void parsesSingleComparison() {
        SelectQuery q = Parser.parse("SELECT name FROM Contact WHERE age > 5").query();
        Comparison c = (Comparison) q.where().orElseThrow();
        assertThat(c.fieldName()).isEqualTo("age");
        assertThat(c.operator()).isEqualTo(Operator.GT);
        assertThat(c.literal()).isEqualTo(new Literal.NumberLiteral(new BigDecimal("5")));
    }

    @Test
    void orBindsLooserThanAnd() {
        // a = 1 AND b = 2 OR c = 3  ==>  (a=1 AND b=2) OR c=3
        SelectQuery q = Parser.parse("SELECT name FROM Contact WHERE age = 1 AND active = TRUE OR name = 'x'").query();
        OrExpr top = (OrExpr) q.where().orElseThrow();
        assertThat(top.left()).isInstanceOf(AndExpr.class);
        assertThat(top.right()).isInstanceOf(Comparison.class);
    }

    @Test
    void parenthesesOverridePrecedence() {
        // a = 1 AND (b = 2 OR c = 3)
        SelectQuery q = Parser.parse("SELECT name FROM Contact WHERE age = 1 AND (active = TRUE OR name = 'x')").query();
        AndExpr top = (AndExpr) q.where().orElseThrow();
        assertThat(top.left()).isInstanceOf(Comparison.class);
        assertThat(top.right()).isInstanceOf(OrExpr.class);
    }

    @Test
    void escapedQuoteInStringLiteral() {
        SelectQuery q = Parser.parse("SELECT name FROM Contact WHERE name = 'O''Brien'").query();
        Comparison c = (Comparison) q.where().orElseThrow();
        assertThat(((Literal.StringLiteral) c.literal()).value()).isEqualTo("O'Brien");
    }

    @Test
    void allSixNumericOperatorsParse() {
        for (String op : new String[] {"=", "!=", "<>", ">", ">=", "<", "<="}) {
            SelectQuery q = Parser.parse("SELECT name FROM Contact WHERE age " + op + " 5").query();
            assertThat(q.where()).isPresent();
        }
    }

    @Test
    void missingFromKeywordIsASyntaxError() {
        assertThatThrownBy(() -> Parser.parse("SELECT name Contact"))
                .isInstanceOf(DslSyntaxException.class);
    }

    @Test
    void trailingGarbageAfterCompleteQueryIsRejected() {
        assertThatThrownBy(() -> Parser.parse("SELECT name FROM Contact WHERE age = 1 GARBAGE"))
                .isInstanceOf(DslSyntaxException.class);
    }

    @Test
    void unterminatedStringLiteralIsRejected() {
        assertThatThrownBy(() -> Parser.parse("SELECT name FROM Contact WHERE name = 'unterminated"))
                .isInstanceOf(DslSyntaxException.class);
    }

    @Test
    void unbalancedParenIsRejected() {
        assertThatThrownBy(() -> Parser.parse("SELECT name FROM Contact WHERE (age = 1"))
                .isInstanceOf(DslSyntaxException.class);
    }
}
