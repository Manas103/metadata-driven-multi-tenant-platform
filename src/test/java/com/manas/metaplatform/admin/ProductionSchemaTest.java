package com.manas.metaplatform.admin;

import com.manas.metaplatform.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production startup path on real PostgreSQL, in its own fresh database: Flyway applies
 * V1, Hibernate validates every entity against it (ddl-auto=validate), the demo loader seeds
 * through the migration's foreign keys, and the API answers. Runs only with the pg profile.
 */
@EnabledIfSystemProperty(named = "spring.profiles.active", matches = ".*pg.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///prodschema?TC_DAEMON=true",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "platform.demo-data=true"})
class ProductionSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TenantRepository tenants;
    @Autowired TestRestTemplate http;

    @Test
    @SuppressWarnings("unchecked")
    void migrationValidatesSeedsAndServes() {
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success", Integer.class));
        assertEquals(8, tenants.count()); // 6 metadata tenants, the sharing tenant, the ledger fund
        assertEquals(DemoDataLoader.LEDGER_ACCOUNTS * 500L,
                jdbc.queryForObject("select count(*) from ledger_entries", Long.class));

        List<Map<String, Object>> list = http.getForObject("/api/admin/tenants", List.class);
        long acme = ((Number) list.get(0).get("id")).longValue();
        Map<String, Object> result = http.postForObject("/api/admin/tenants/" + acme + "/query",
                Map.of("dsl", "SELECT name FROM Contact WHERE name != ''"), Map.class);
        assertTrue(((List<?>) result.get("rows")).size() > 0);
        assertTrue(http.getForObject("/actuator/health", String.class).contains("UP"));
    }
}
