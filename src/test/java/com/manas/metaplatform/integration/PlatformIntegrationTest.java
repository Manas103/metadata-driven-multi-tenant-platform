package com.manas.metaplatform.integration;

import com.manas.metaplatform.catalog.UnknownObjectException;
import com.manas.metaplatform.compiler.CompiledSql;
import com.manas.metaplatform.compiler.QueryExecutor;
import com.manas.metaplatform.generator.DataSeeder;
import com.manas.metaplatform.generator.SeedDataset;
import com.manas.metaplatform.reference.ReferenceInterpreter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end wiring test against the real Spring context and an H2 database in
 * PostgreSQL-compatibility mode (see {@code src/test/resources/application.yml}): seeds the
 * real multi-tenant dataset once, then exercises the compiler and the independent reference
 * interpreter against it. The 2,000-query and 500-probe scale claims are measured
 * separately by {@code bench.BenchmarkRunner}; this test is the fast, always-on
 * confirmation that the wiring itself is correct.
 */
@SpringBootTest
@Transactional
class PlatformIntegrationTest {

    @Autowired
    private DataSeeder dataSeeder;
    @Autowired
    private QueryExecutor queryExecutor;
    @Autowired
    private ReferenceInterpreter referenceInterpreter;

    private static SeedDataset dataset;

    @Autowired
    void seedOnce(DataSeeder seeder) {
        if (dataset == null) {
            dataset = seeder.seed(42L);
        }
    }

    @Test
    void compilerAndReferenceInterpreterAgreeOnASimpleQuery() {
        long acmeId = dataset.tenantIdByName().get("Acme");
        List<Map<String, Object>> compiled = queryExecutor.execute(
                "SELECT name, age FROM Contact WHERE age >= 0", acmeId);
        List<Map<String, Object>> reference = referenceInterpreter.execute(
                "SELECT name, age FROM Contact WHERE age >= 0", acmeId);
        assertThat(compiled).isEqualTo(reference);
        assertThat(compiled).isNotEmpty();
    }

    @Test
    void everyReturnedRowActuallyBelongsToTheQueryingTenant() {
        long acmeId = dataset.tenantIdByName().get("Acme");
        long globexId = dataset.tenantIdByName().get("Globex");
        List<Map<String, Object>> acmeRows = queryExecutor.execute("SELECT name FROM Contact", acmeId);
        List<Map<String, Object>> globexRows = queryExecutor.execute("SELECT name FROM Contact", globexId);
        // Both tenants declared an object literally named "Contact"; each tenant's query must
        // resolve to its own object definition id, never the other's, even though the names collide.
        assertThat(acmeRows).isNotEmpty();
        assertThat(globexRows).isNotEmpty();
        assertThat(acmeRows).doesNotContainAnyElementsOf(globexRows);
    }

    @Test
    void queryingAnotherTenantsExclusiveObjectNameIsRejected() {
        long acmeId = dataset.tenantIdByName().get("Acme");
        assertThatThrownBy(() -> queryExecutor.execute("SELECT codeword FROM GlobexSecret", acmeId))
                .isInstanceOf(UnknownObjectException.class);
    }

    @Test
    void compiledSqlNeverInterpolatesTheTenantIdIntoTheSqlText() {
        long acmeId = dataset.tenantIdByName().get("Acme");
        CompiledSql compiled = queryExecutor.compileOnly("SELECT name FROM Contact", acmeId);
        // A word-boundary check, not a bare substring check: table/column aliases like "fv1" can
        // coincidentally contain the tenant id's digits, so this asserts no standalone numeric
        // token in the SQL text equals the tenant id, rather than merely that no substring does.
        assertThat(compiled.sql()).doesNotContainPattern("(?<![A-Za-z0-9_])" + acmeId + "(?![0-9])");
        assertThat(compiled.sql()).contains("r.tenant_id = ?");
        assertThat(compiled.params()).contains((Object) acmeId);
    }
}
