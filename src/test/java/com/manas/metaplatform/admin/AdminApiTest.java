package com.manas.metaplatform.admin;

import com.manas.metaplatform.bookofrecord.generator.BookOfRecordDataSeeder;
import com.manas.metaplatform.bookofrecord.repository.LedgerEntryRepository;
import com.manas.metaplatform.domain.AppUser;
import com.manas.metaplatform.domain.Role;
import com.manas.metaplatform.domain.Tenant;
import com.manas.metaplatform.reference.ReferenceInterpreter;
import com.manas.metaplatform.repository.AppUserRepository;
import com.manas.metaplatform.repository.RoleRepository;
import com.manas.metaplatform.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's API end to end over HTTP: every tenant and user here is created by the test
 * itself, so it does not depend on what other test classes seeded into the shared context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminApiTest {

    @Autowired TestRestTemplate http;
    @Autowired TenantRepository tenants;
    @Autowired RoleRepository roles;
    @Autowired AppUserRepository users;
    @Autowired BookOfRecordDataSeeder ledgerSeeder;
    @Autowired LedgerEntryRepository ledger;
    @Autowired ReferenceInterpreter oracle;

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) {
        ResponseEntity<Map> r = http.postForEntity(path, body, Map.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), path + " -> " + r.getStatusCode() + " " + r.getBody());
        return r.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(long tenantId, String dsl, Long viewer) {
        Map<String, Object> body = new HashMap<>();
        body.put("dsl", dsl);
        body.put("viewerUserId", viewer);
        return (List<Map<String, Object>>) post("/api/admin/tenants/" + tenantId + "/query", body).get("rows");
    }

    @Test
    void sameObjectNameInTwoTenantsNeverLeaksAcrossTheApi() {
        long a = tenants.save(new Tenant("ApiTenantA")).getId();
        long b = tenants.save(new Tenant("ApiTenantB")).getId();
        for (long t : List.of(a, b)) {
            post("/api/admin/tenants/" + t + "/objects", Map.of("name", "Account"));
            post("/api/admin/tenants/" + t + "/objects/Account/fields", Map.of("name", "name", "type", "STRING"));
        }
        post("/api/admin/tenants/" + a + "/objects/Account/records", Map.of("values", Map.of("name", "a-only")));
        post("/api/admin/tenants/" + b + "/objects/Account/records", Map.of("values", Map.of("name", "b-secret")));

        // tenant A asks for tenant B's value by literal, and with an OR that would escape a naive AND
        assertEquals(0, rows(a, "SELECT name FROM Account WHERE name = 'b-secret'", null).size());
        assertEquals(List.of("a-only"), rows(a, "SELECT name FROM Account WHERE name = 'x' OR name != 'x'", null)
                .stream().map(r -> r.get("name")).toList());
        assertEquals(1, rows(b, "SELECT name FROM Account WHERE name = 'b-secret'", null).size());
    }

    @Test
    void addingAFieldIsAMetadataRowAndOldRecordsReadItAsNull() {
        long t = tenants.save(new Tenant("ApiTenantFields")).getId();
        post("/api/admin/tenants/" + t + "/objects", Map.of("name", "Device"));
        post("/api/admin/tenants/" + t + "/objects/Device/fields", Map.of("name", "serial", "type", "STRING"));
        post("/api/admin/tenants/" + t + "/objects/Device/records", Map.of("values", Map.of("serial", "S-1")));

        post("/api/admin/tenants/" + t + "/objects/Device/fields", Map.of("name", "firmware", "type", "NUMBER"));
        post("/api/admin/tenants/" + t + "/objects/Device/records",
                Map.of("values", Map.of("serial", "S-2", "firmware", 7)));

        List<Map<String, Object>> all = rows(t, "SELECT serial, firmware FROM Device WHERE serial != ''", null);
        assertEquals(2, all.size());
        assertNull(all.get(0).get("firmware"));
        assertEquals(1, rows(t, "SELECT serial FROM Device WHERE firmware >= 7", null).size());
    }

    @Test
    void aViewerSeesOwnAndSubordinatesRecordsOnly() {
        long t = tenants.save(new Tenant("ApiTenantSharing")).getId();
        long mgrRole = roles.save(new Role(t, "Manager", null)).getId();
        long repRole = roles.save(new Role(t, "Rep", mgrRole)).getId();
        long mgr = users.save(new AppUser(t, "manager", mgrRole)).getId();
        long rep1 = users.save(new AppUser(t, "rep1", repRole)).getId();
        long rep2 = users.save(new AppUser(t, "rep2", repRole)).getId();
        post("/api/admin/tenants/" + t + "/objects", Map.of("name", "Deal"));
        post("/api/admin/tenants/" + t + "/objects/Deal/fields", Map.of("name", "code", "type", "STRING"));
        post("/api/admin/tenants/" + t + "/objects/Deal/records", Map.of("values", Map.of("code", "D1"), "ownerUserId", rep1));
        post("/api/admin/tenants/" + t + "/objects/Deal/records", Map.of("values", Map.of("code", "D2"), "ownerUserId", rep2));

        String q = "SELECT code FROM Deal WHERE code != ''";
        assertEquals(List.of("D1"), rows(t, q, rep1).stream().map(r -> r.get("code")).toList());
        assertEquals(List.of("D2"), rows(t, q, rep2).stream().map(r -> r.get("code")).toList());
        assertEquals(2, rows(t, q, mgr).size());
        assertEquals(2, rows(t, q, null).size()); // tenant scoping only
        // a same-role peer is denied by the independent oracle too, not only by the compiler
        assertEquals(List.of("D1"), oracle.executeWithSharing(q, t, rep1).stream().map(r -> r.get("code")).toList());
        assertEquals(2, oracle.executeWithSharing(q, t, mgr).size());
    }

    @Test
    void badQueriesAndForeignViewersAre400() {
        long t = tenants.save(new Tenant("ApiTenantErrors")).getId();
        long other = tenants.save(new Tenant("ApiTenantOther")).getId();
        long role = roles.save(new Role(other, "R", null)).getId();
        long foreignUser = users.save(new AppUser(other, "outsider", role)).getId();
        String path = "/api/admin/tenants/" + t + "/query";
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity(path, Map.of("dsl", "SELEC x FROM"), Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity(path, Map.of("dsl", "SELECT a FROM Nope WHERE a = 1"), Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity(path,
                Map.of("dsl", "SELECT a FROM Nope WHERE a = 1", "viewerUserId", foreignUser), Map.class).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, http.postForEntity("/api/admin/tenants/" + t + "/objects",
                Map.of("name", "bad name; DROP"), Map.class).getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCorrectionIsANewVersionAndThePastBeliefStaysReadable() throws Exception {
        long t = tenants.save(new Tenant("ApiLedger")).getId();
        long account = ledgerSeeder.seed(t, 7L, 1).accountIds().get(0);
        Map<String, Object> entry = ((List<Map<String, Object>>) (List<?>) http.getForObject(
                "/api/bookofrecord/accounts/" + account + "/entries?asOf=2030-01-01", List.class)).get(0);
        String symbol = (String) entry.get("symbol");
        String factId = ledger.findAllByAccount(account).stream()
                .filter(e -> e.getAsOfTo() == null && e.getSymbol().equals(symbol)).findFirst().orElseThrow().getFactId();
        int versionsBefore = http.getForObject("/api/bookofrecord/facts/" + factId + "/versions", List.class).size();

        Instant before = Instant.now();
        Thread.sleep(5);
        post("/api/bookofrecord/restatements/" + factId, Map.of("quantity", 123456));

        List<Map<String, Object>> versions = http.getForObject("/api/bookofrecord/facts/" + factId + "/versions", List.class);
        assertEquals(versionsBefore + 1, versions.size());
        assertEquals(1, versions.stream().filter(v -> v.get("asOfTo") == null).count());

        String url = "/api/bookofrecord/accounts/" + account + "/entries?asOf=2030-01-01&believedAt=";
        List<Map<String, Object>> then = http.getForObject(url + before, List.class);
        List<Map<String, Object>> now = http.getForObject(url + Instant.now(), List.class);
        assertEquals(then.size(), now.size());
        assertEquals(0, then.stream().filter(v -> ((Number) v.get("quantity")).intValue() == 123456).count());
        assertEquals(1, now.stream().filter(v -> ((Number) v.get("quantity")).intValue() == 123456).count());
    }
}
