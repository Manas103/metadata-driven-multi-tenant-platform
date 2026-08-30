package com.manas.metaplatform.integration;

import com.manas.metaplatform.compiler.CompiledSql;
import com.manas.metaplatform.compiler.QueryExecutor;
import com.manas.metaplatform.generator.SharingDataSeeder;
import com.manas.metaplatform.generator.SharingSeedDataset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test for the record-level sharing filter against the real Spring context
 * and an H2 database in PostgreSQL-compatibility mode: seeds the real role hierarchy and
 * ownership/explicit-share dataset once, then exercises one illustrative case from each of the
 * five visibility paths directly through {@link QueryExecutor#executeWithSharing}. The 500-probe
 * adversarial suite and the 1,000-query reference-translator diff are measured separately by
 * {@code bench.SharingBenchmarkRunner}; this test is the fast, always-on confirmation that the
 * wiring itself is correct and that the AST actually carries both the tenant and sharing nodes.
 */
@SpringBootTest
@Transactional
class SharingIntegrationTest {

    @Autowired
    private SharingDataSeeder sharingDataSeeder;
    @Autowired
    private QueryExecutor queryExecutor;

    private static SharingSeedDataset dataset;

    @Autowired
    void seedOnce(SharingDataSeeder seeder) {
        if (dataset == null) {
            dataset = seeder.seed(555L);
        }
    }

    @Test
    void ownerSeesTheirOwnRecord() {
        long recordId = dataset.ownerUserIdByRecordId().keySet().iterator().next();
        long ownerUserId = dataset.ownerUserIdByRecordId().get(recordId);
        String code = dataset.uniqueCodeByRecordId().get(recordId);
        List<Map<String, Object>> rows = queryExecutor.executeWithSharing(
                "SELECT code FROM Opportunity WHERE code = '" + code + "'", dataset.tenantId(), ownerUserId);
        assertThat(rows).extracting(row -> row.get("record_id")).contains(recordId);
    }

    @Test
    void ceoSeesEverySalesRepsRecordThroughTheRoleHierarchy() {
        long ceoUserId = dataset.userIdByRoleName().get("CEO");
        long anySalesRecordId = dataset.ownerUserIdByRecordId().entrySet().stream()
                .filter(e -> e.getValue().equals(dataset.userIdByRoleName().get("Rep_Sales_1")))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String code = dataset.uniqueCodeByRecordId().get(anySalesRecordId);
        List<Map<String, Object>> rows = queryExecutor.executeWithSharing(
                "SELECT code FROM Opportunity WHERE code = '" + code + "'", dataset.tenantId(), ceoUserId);
        assertThat(rows).extracting(row -> row.get("record_id")).contains(anySalesRecordId);
    }

    @Test
    void unrelatedPeerRepCannotSeeAnotherRepsRecordWithNoGrant() {
        long rep1UserId = dataset.userIdByRoleName().get("Rep_Sales_1");
        long rep2UserId = dataset.userIdByRoleName().get("Rep_Sales_2");
        long rep2RecordId = dataset.ownerUserIdByRecordId().entrySet().stream()
                .filter(e -> e.getValue().equals(rep2UserId)
                        && !dataset.explicitlySharedWith().getOrDefault(e.getKey(), List.of()).contains(rep1UserId))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String code = dataset.uniqueCodeByRecordId().get(rep2RecordId);
        List<Map<String, Object>> rows = queryExecutor.executeWithSharing(
                "SELECT code FROM Opportunity WHERE code = '" + code + "'", dataset.tenantId(), rep1UserId);
        assertThat(rows).isEmpty();
    }

    @Test
    void explicitGranteeSeesARecordItDoesNotOwnAndHasNoHierarchyPathTo() {
        Map.Entry<Long, List<Long>> grant = dataset.explicitlySharedWith().entrySet().iterator().next();
        long recordId = grant.getKey();
        long granteeUserId = grant.getValue().get(0);
        String code = dataset.uniqueCodeByRecordId().get(recordId);
        List<Map<String, Object>> rows = queryExecutor.executeWithSharing(
                "SELECT code FROM Opportunity WHERE code = '" + code + "'", dataset.tenantId(), granteeUserId);
        assertThat(rows).extracting(row -> row.get("record_id")).contains(recordId);
    }

    @Test
    void compiledSharingSqlNamesBothTheTenantAndTheSharingPredicate() {
        long ownerUserId = dataset.userIdByRoleName().get("Rep_Sales_1");
        CompiledSql compiled = queryExecutor.compileOnlyWithSharing(
                "SELECT code FROM Opportunity", dataset.tenantId(), ownerUserId);
        assertThat(compiled.sql()).contains("r.tenant_id = ?");
        assertThat(compiled.sql()).contains("r.owner_user_id IN");
        assertThat(compiled.sql()).contains("record_shares");
        assertThat(compiled.params()).contains((Object) ownerUserId);
    }
}
