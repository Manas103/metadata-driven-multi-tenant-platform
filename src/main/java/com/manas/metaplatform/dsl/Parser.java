package com.manas.metaplatform.dsl;

import com.manas.metaplatform.dsl.ast.AndExpr;
import com.manas.metaplatform.dsl.ast.Comparison;
import com.manas.metaplatform.dsl.ast.Expr;
import com.manas.metaplatform.dsl.ast.Literal;
import com.manas.metaplatform.dsl.ast.Operator;
import com.manas.metaplatform.dsl.ast.OrExpr;
import com.manas.metaplatform.dsl.ast.SelectQuery;
import com.manas.metaplatform.dsl.lexer.Lexer;
import com.manas.metaplatform.dsl.lexer.Token;
import com.manas.metaplatform.dsl.lexer.TokenType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A hand-written recursive-descent parser producing a real AST (see {@code dsl.ast}), not
 * a regular-expression pass over the query text. Grammar, in the style of SOQL/SQL:
 *
 * <pre>
 * query      := "SELECT" fieldList "FROM" IDENT ( "WHERE" orExpr )?
 * fieldList  := IDENT ( "," IDENT )*
 * orExpr     := andExpr ( "OR" andExpr )*
 * andExpr    := primary ( "AND" primary )*
 * primary    := "(" orExpr ")" | comparison
 * comparison := IDENT operator literal
 * operator   := "=" | "!=" | "&lt;&gt;" | "&gt;" | "&gt;=" | "&lt;" | "&lt;=" | "LIKE"
 * literal    := STRING | NUMBER | "TRUE" | "FALSE"
 * </pre>
 *
 * OR binds more loosely than AND, matching conventional SQL precedence, so
 * {@code a = 1 AND b = 2 OR c = 3} parses as {@code (a = 1 AND b = 2) OR c = 3}.
 */
public final class Parser {

    private final List<Token> tokens;
    private int pos;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public static ParsedQuery parse(String dsl) {
        List<Token> tokens = new Lexer(dsl).tokenize();
        Parser parser = new Parser(tokens);
        SelectQuery query = parser.parseQuery();
        parser.expect(TokenType.EOF, "unexpected trailing input after a complete query");
        return new ParsedQuery(query);
    }

    private SelectQuery parseQuery() {
        expect(TokenType.SELECT, "expected SELECT");
        List<String> fields = parseFieldList();
        expect(TokenType.FROM, "expected FROM");
        Token objectName = expect(TokenType.IDENT, "expected an object name after FROM");
        Optional<Expr> where = Optional.empty();
        if (check(TokenType.WHERE)) {
            advance();
            where = Optional.of(parseOrExpr());
        }
        return new SelectQuery(fields, objectName.text(), where);
    }

    private List<String> parseFieldList() {
        List<String> fields = new ArrayList<>();
        fields.add(expect(TokenType.IDENT, "expected a field name after SELECT").text());
        while (check(TokenType.COMMA)) {
            advance();
            fields.add(expect(TokenType.IDENT, "expected a field name after ','").text());
        }
        return fields;
    }

    private Expr parseOrExpr() {
        Expr left = parseAndExpr();
        while (check(TokenType.OR)) {
            advance();
            Expr right = parseAndExpr();
            left = new OrExpr(left, right);
        }
        return left;
    }

    private Expr parseAndExpr() {
        Expr left = parsePrimary();
        while (check(TokenType.AND)) {
            advance();
            Expr right = parsePrimary();
            left = new AndExpr(left, right);
        }
        return left;
    }

    private Expr parsePrimary() {
        if (check(TokenType.LPAREN)) {
            advance();
            Expr inner = parseOrExpr();
            expect(TokenType.RPAREN, "expected ')' to close '('");
            return inner;
        }
        return parseComparison();
    }

    private Expr parseComparison() {
        Token field = expect(TokenType.IDENT, "expected a field name in a predicate");
        Operator operator = parseOperator();
        Literal literal = parseLiteral();
        return new Comparison(field.text(), operator, literal);
    }

    private Operator parseOperator() {
        Token t = advance();
        return switch (t.type()) {
            case EQ -> Operator.EQ;
            case NEQ -> Operator.NEQ;
            case GT -> Operator.GT;
            case GTE -> Operator.GTE;
            case LT -> Operator.LT;
            case LTE -> Operator.LTE;
            case LIKE -> Operator.LIKE;
            default -> throw new DslSyntaxException(
                    "expected a comparison operator at position " + t.position() + ", found '" + t.text() + "'");
        };
    }

    private Literal parseLiteral() {
        Token t = advance();
        return switch (t.type()) {
            case STRING -> new Literal.StringLiteral(t.text());
            case NUMBER -> new Literal.NumberLiteral(new BigDecimal(t.text()));
            case TRUE -> new Literal.BooleanLiteral(true);
            case FALSE -> new Literal.BooleanLiteral(false);
            default -> throw new DslSyntaxException(
                    "expected a literal (string, number or boolean) at position " + t.position()
                            + ", found '" + t.text() + "'");
        };
    }

    private boolean check(TokenType type) {
        return tokens.get(pos).type() == type;
    }

    private Token advance() {
        Token t = tokens.get(pos);
        if (t.type() != TokenType.EOF) {
            pos++;
        }
        return t;
    }

    private Token expect(TokenType type, String message) {
        Token t = tokens.get(pos);
        if (t.type() != type) {
            throw new DslSyntaxException(message + " at position " + t.position() + ", found '" + t.text() + "'");
        }
        return advance();
    }
}
