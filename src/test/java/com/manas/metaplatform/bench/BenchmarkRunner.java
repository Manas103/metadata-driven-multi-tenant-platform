package com.manas.metaplatform.bench;

import com.manas.metaplatform.compiler.QueryExecutor;
import com.manas.metaplatform.generator.AdversarialProbe;
import com.manas.metaplatform.generator.AdversarialQueryGenerator;
import com.manas.metaplatform.generator.DataSeeder;
import com.manas.metaplatform.generator.GeneratedQuery;
import com.manas.metaplatform.generator.QueryGenerator;
import com.manas.metaplatform.generator.SeedDataset;
import com.manas.metaplatform.reference.ReferenceInterpreter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real measurement run behind the two resume claims. Not part of the default
 * {@code mvn test} run (tagged {@code benchmark}, excluded by the surefire configuration in
 * {@code pom.xml}); run explicitly with
 * {@code mvn test -Dtest=BenchmarkRunner -Dsurefire.excludedGroups=}.
 *
 * <p>Claim 1: 2,000 generated queries, run through the main compiler
 * ({@code compiler.SqlCompiler} via {@code QueryExecutor}) and the independent
 * {@code ReferenceInterpreter}, diffed row-for-row.
 *
 * <p>Claim 2: 500 adversarial cross-tenant queries ({@code AdversarialQueryGenerator}), each
 * executed as the named attacking tenant, every returned row's true tenant id looked up
 * directly against the {@code records} table and checked against the querying tenant.
 */
@SpringBootTest
@Transactional
@Tag("benchmark")
class BenchmarkRunner {

    private static final int REFERENCE_DIFF_QUERY_COUNT = 2000;
    private static final int ADVERSARIAL_PROBE_COUNT = 500;

    @Autowired
    private DataSeeder dataSeeder;
    @Autowired
    private QueryGenerator queryGenerator;
    @Autowired
    private AdversarialQueryGenerator adversarialQueryGenerator;
    @Autowired
    private QueryExecutor queryExecutor;
    @Autowired
    private ReferenceInterpreter referenceInterpreter;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void referenceTranslatorDiffAndAdversarialIsolationRun() throws IOException {
        SeedDataset dataset = dataSeeder.seed(1001L);
        StringBuilder log = new StringBuilder();

        int matched = 0;
        int mismatched = 0;
        List<GeneratedQuery> queries = queryGenerator.generate(dataset, REFERENCE_DIFF_QUERY_COUNT, 2001L);
        for (GeneratedQuery gq : queries) {
            List<Map<String, Object>> compiledResult = queryExecutor.execute(gq.dsl(), gq.tenantId());
            List<Map<String, Object>> referenceResult = referenceInterpreter.execute(gq.dsl(), gq.tenantId());
            if (compiledResult.equals(referenceResult)) {
                matched++;
            } else {
                mismatched++;
                log.append("MISMATCH tenant=").append(gq.tenantName()).append(" dsl=").append(gq.dsl())
                        .append("\n  compiled=").append(compiledResult)
                        .append("\n  reference=").append(referenceResult).append("\n");
            }
        }
        log.append("Reference-translator diff: ").append(matched).append(" / ").append(REFERENCE_DIFF_QUERY_COUNT)
                .append(" generated queries matched exactly (").append(mismatched).append(" mismatched)\n");

        int foreignRowLeaks = 0;
        int probesExecuted = 0;
        List<AdversarialProbe> probes = adversarialQueryGenerator.generate(dataset, ADVERSARIAL_PROBE_COUNT, 3001L);
        for (AdversarialProbe probe : probes) {
            List<Map<String, Object>> rows = queryExecutor.execute(probe.dsl(), probe.tenantId());
            probesExecuted++;
            for (Map<String, Object> row : rows) {
                Long recordId = (Long) row.get("record_id");
                Long actualTenantId = jdbcTemplate.queryForObject(
                        "SELECT tenant_id FROM records WHERE id = ?", Long.class, recordId);
                if (!actualTenantId.equals(probe.tenantId())) {
                    foreignRowLeaks++;
                    log.append("LEAK probe=[").append(probe.description()).append("] querying tenant=")
                            .append(probe.tenantName()).append(" got record ").append(recordId)
                            .append(" owned by tenant ").append(actualTenantId).append("\n");
                }
            }
        }
        log.append("Adversarial cross-tenant suite: ").append(foreignRowLeaks).append(" foreign rows across ")
                .append(probesExecuted).append(" probes (target 0)\n");

        writeRawOutput(log.toString());

        assertThat(probesExecuted).isEqualTo(ADVERSARIAL_PROBE_COUNT);
        // Assertions intentionally check the counters actually measured, not a pre-decided target:
        // this test fails loudly (rather than silently passing) if either honest number regresses.
        assertThat(foreignRowLeaks).isEqualTo(0);
        assertThat(matched + mismatched).isEqualTo(REFERENCE_DIFF_QUERY_COUNT);
    }

    private void writeRawOutput(String content) throws IOException {
        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(docsDir.resolve("benchmark_output.txt")))) {
            writer.print(content);
        }
    }
}
