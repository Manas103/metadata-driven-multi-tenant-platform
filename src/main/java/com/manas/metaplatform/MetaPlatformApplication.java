package com.manas.metaplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. The application itself is a thin JDBC/JPA shell around the DSL parser,
 * the tenant-scoping rewriter and the SQL compiler in {@code dsl} / {@code compiler};
 * see the README for the architecture and the measured claims this repository backs.
 */
@SpringBootApplication
public class MetaPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(MetaPlatformApplication.class, args);
    }
}
