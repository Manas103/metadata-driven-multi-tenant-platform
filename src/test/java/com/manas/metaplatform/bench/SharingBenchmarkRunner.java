package com.manas.metaplatform.bench;

import com.manas.metaplatform.compiler.QueryExecutor;
import com.manas.metaplatform.generator.GeneratedSharingQuery;
import com.manas.metaplatform.generator.SharingDataSeeder;
import com.manas.metaplatform.generator.SharingProbe;
import com.manas.metaplatform.generator.SharingProbeGenerator;
import com.manas.metaplatform.generator.SharingQueryGenerator;
import com.manas.metaplatform.generator.SharingSeedDataset;
import com.manas.metaplatform.reference.ReferenceInterpreter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real measurement run behind the record-level sharing filter claim, entirely separate
 * from {@link BenchmarkRunner} (a different seeder, a different {@code Random} sequence,
 * a different tenant), so this run does not disturb the original tenant-isolation numbers and
 * a failure here cannot hide behind, or be hidden by, that suite. Not part of the default
 * {@code mvn test} run (tagged {@code benchmark}); run explicitly with
 * {@code mvn test -Dtest=SharingBenchmarkRunner -Dsurefire.excludedGroups=}.
 *
 * <p>Claim 1: an adversarial sharing-visibility probe suite (owner, role-hierarchy ancestor,
 * explicit share, same-tier peer denial, cross-branch denial) checked against an independently
 * computed ground truth.
 *
 * <p>Claim 2: a reference-translator diff over sharing-scoped queries, {@link QueryExecutor
 * #executeWithSharing} against {@link ReferenceInterpreter#executeWithSharing}, which computes
 * the same OR-of-ownership/hierarchy/explicit-share visibility from the opposite direction
 * (walking upward from each row's owner's role rather than downward from the viewer's role) over
 * data pulled with its own {@code JdbcTemplate} queries, sharing no code with
 * {@code sharing.RoleHierarchyResolver} or {@code sharing.SharingScopingRewriter}.
 */
@SpringBootTest
@Transactional
@Tag("benchmark")
class SharingBenchmarkRunner {

    private static final int ADVERSARIAL_PROBE_COUNT = 500;
    private static final int REFERENCE_DIFF_QUERY_COUNT = 1000;

    @Autowired
    private SharingDataSeeder sharingDataSeeder;
    @Autowired
    private SharingProbeGenerator sharingProbeGenerator;
    @Autowired
    private SharingQueryGenerator sharingQueryGenerator;
    @Autowired
    private QueryExecutor queryExecutor;
    @Autowired
    private ReferenceInterpreter referenceInterpreter;

    @Test
    void sharingVisibilityAdversarialSuiteAndReferenceDiff() throws IOException {
        SharingSeedDataset dataset = sharingDataSeeder.seed(9001L);
        StringBuilder log = new StringBuilder();

        int leaks = 0;
        int wrongfulDenials = 0;
        int probesRun = 0;
        Map<String, int[]> byCategory = new java.util.LinkedHashMap<>(); // [leaks, wrongfulDenials, total]
        List<SharingProbe> probes = sharingProbeGenerator.generate(dataset, ADVERSARIAL_PROBE_COUNT, 9101L);
        for (SharingProbe probe : probes) {
            List<Map<String, Object>> rows =
                    queryExecutor.executeWithSharing(probe.dsl(), dataset.tenantId(), probe.viewerUserId());
            boolean actuallyVisible = rows.stream()
                    .anyMatch(row -> ((Long) row.get("record_id")) == probe.targetRecordId());
            probesRun++;
            byCategory.computeIfAbsent(probe.category(), k -> new int[3]);
            byCategory.get(probe.category())[2]++;
            if (actuallyVisible && !probe.expectedVisible()) {
                leaks++;
                byCategory.get(probe.category())[0]++;
                log.append("LEAK category=").append(probe.category()).append(" viewer=").append(probe.viewerLabel())
                        .append(" saw record ").append(probe.targetRecordId())
                        .append(" (").append(probe.description()).append(")\n");
            } else if (!actuallyVisible && probe.expectedVisible()) {
                wrongfulDenials++;
                byCategory.get(probe.category())[1]++;
                log.append("WRONGFUL DENIAL category=").append(probe.category()).append(" viewer=")
                        .append(probe.viewerLabel()).append(" could not see record ").append(probe.targetRecordId())
                        .append(" (").append(probe.description()).append(")\n");
            }
        }
        log.append("Sharing-visibility adversarial suite: ").append(leaks).append(" leaks, ")
                .append(wrongfulDenials).append(" wrongful denials, across ").append(probesRun)
                .append(" probes (target 0 leaks)\n");
        for (Map.Entry<String, int[]> entry : byCategory.entrySet()) {
            log.append("  category=").append(entry.getKey()).append(" leaks=").append(entry.getValue()[0])
                    .append(" wrongful_denials=").append(entry.getValue()[1]).append(" total=")
                    .append(entry.getValue()[2]).append("\n");
        }

        int matched = 0;
        int mismatched = 0;
        List<GeneratedSharingQuery> diffQueries =
                sharingQueryGenerator.generate(dataset, REFERENCE_DIFF_QUERY_COUNT, 9201L);
        for (GeneratedSharingQuery gq : diffQueries) {
            List<Map<String, Object>> compiled =
                    queryExecutor.executeWithSharing(gq.dsl(), gq.tenantId(), gq.viewerUserId());
            List<Map<String, Object>> reference =
                    referenceInterpreter.executeWithSharing(gq.dsl(), gq.tenantId(), gq.viewerUserId());
            if (compiled.equals(reference)) {
                matched++;
            } else {
                mismatched++;
                log.append("MISMATCH viewer=").append(gq.viewerLabel()).append(" dsl=").append(gq.dsl())
                        .append("\n  compiled=").append(compiled).append("\n  reference=").append(reference)
                        .append("\n");
            }
        }
        log.append("Sharing-filter reference-translator diff: ").append(matched).append(" / ")
                .append(REFERENCE_DIFF_QUERY_COUNT).append(" generated queries matched exactly (")
                .append(mismatched).append(" mismatched)\n");

        writeRawOutput(log.toString());

        assertThat(probesRun).isEqualTo(ADVERSARIAL_PROBE_COUNT);
        // These assertions check the counters actually measured, not a pre-decided target: this
        // test fails loudly, rather than silently passing, if either honest number regresses.
        assertThat(leaks).isEqualTo(0);
        assertThat(matched + mismatched).isEqualTo(REFERENCE_DIFF_QUERY_COUNT);
        assertThat(mismatched).isEqualTo(0);
    }

    private void writeRawOutput(String content) throws IOException {
        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        try (PrintWriter writer =
                     new PrintWriter(Files.newBufferedWriter(docsDir.resolve("sharing_benchmark_output.txt")))) {
            writer.print(content);
        }
    }
}
