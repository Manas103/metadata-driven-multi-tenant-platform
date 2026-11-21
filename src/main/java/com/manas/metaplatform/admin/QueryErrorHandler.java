package com.manas.metaplatform.admin;

import com.manas.metaplatform.catalog.UnknownFieldException;
import com.manas.metaplatform.catalog.UnknownObjectException;
import com.manas.metaplatform.compiler.DslCompileException;
import com.manas.metaplatform.dsl.DslSyntaxException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** A query the DSL rejects is the caller's mistake: 400 with the parser's or catalog's message. */
@RestControllerAdvice(assignableTypes = AdminController.class)
public class QueryErrorHandler {

    @ExceptionHandler({DslSyntaxException.class, DslCompileException.class,
            UnknownObjectException.class, UnknownFieldException.class})
    public ResponseEntity<Map<String, String>> badQuery(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
