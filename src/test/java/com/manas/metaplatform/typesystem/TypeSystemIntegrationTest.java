package com.manas.metaplatform.typesystem;

import com.manas.metaplatform.typesystem.web.TypeRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fast, always-on wiring check for the type platform: confirms the DSL file at
 * {@code src/main/resources/typesystem/declared-types.dsl} parses to exactly 40 declared
 * types, that {@code TypeSystemBootstrap} actually ran the emitted DDL for each of them (the
 * context would fail to start otherwise), and that one generated Java client
 * (loaded the same reflective way the differential benchmark loads all 40, see
 * {@code TypePlatformBenchmarkRunner}) can create and read back a real record through the
 * REST surface. The 2,000-query cross-client differential run lives in
 * {@code TypePlatformBenchmarkRunner}, tagged {@code benchmark} and excluded from this
 * default run for the same reason {@code BenchmarkRunner} is.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TypeSystemIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TypeRegistry registry;

    @Test
    void exactlyFortyDeclaredTypesRegistered() {
        assertEquals(40, registry.size());
        assertTrue(registry.all().stream().anyMatch(t -> t.name().equals("Asset")));
    }

    @Test
    void javaClientCreatesAndReadsBackThroughTheRestSurface() throws Exception {
        var client = TypePlatformClientLoader.load("Asset", "http://localhost:" + port);
        Map<String, Object> created = client.create(Map.of(
                "name", "Line 3 Compressor",
                "assetTag", "AST-0001",
                "purchaseValue", 18450.0,
                "active", true));
        long id = ((Number) created.get("id")).longValue();
        Map<String, Object> fetched = client.getById(id);
        assertEquals("Line 3 Compressor", fetched.get("name"));
        assertEquals(true, fetched.get("active"));
    }
}
