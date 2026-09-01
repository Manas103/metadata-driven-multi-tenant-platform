package com.manas.metaplatform.typesystem.web;

import com.manas.metaplatform.typesystem.compiler.DdlEmitter;
import com.manas.metaplatform.typesystem.compiler.TypePlatformCompiler;
import com.manas.metaplatform.typesystem.dsl.TypeDecl;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Runs once per application context startup: reads the one DSL source file, parses it,
 * emits and executes the {@code CREATE TABLE} DDL for every declared type against whatever
 * {@link JdbcTemplate} this context is wired to (H2 in PostgreSQL-compatibility mode for
 * tests, real PostgreSQL in the production profile), and registers the parsed types into
 * {@link TypeRegistry} so {@link GenericTypeController} can serve them. Tables are dropped
 * and recreated on every startup (a test-friendly choice, not a production migration
 * strategy; see the README's "Honest framing" section), which is also what makes the DDL
 * itself part of what every test run actually exercises rather than a document nobody runs.
 */
@Component
public final class TypeSystemBootstrap {

    private static final String DSL_RESOURCE = "typesystem/declared-types.dsl";

    private final TypePlatformCompiler compiler = new TypePlatformCompiler();
    private final JdbcTemplate jdbcTemplate;
    private final TypeRegistry registry;

    @Autowired
    public TypeSystemBootstrap(JdbcTemplate jdbcTemplate, TypeRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    @PostConstruct
    public void bootstrap() {
        List<TypeDecl> types = compiler.parse(readDslSource());
        DdlEmitter ddlEmitter = compiler.ddlEmitter();
        for (TypeDecl type : types) {
            jdbcTemplate.execute(ddlEmitter.emitDropTableIfExists(type));
            jdbcTemplate.execute(ddlEmitter.emitCreateTable(type));
        }
        registry.register(types);
    }

    private String readDslSource() {
        try (var input = new ClassPathResource(DSL_RESOURCE).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
