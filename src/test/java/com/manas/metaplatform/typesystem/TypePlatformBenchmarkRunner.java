package com.manas.metaplatform.typesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manas.metaplatform.typesystem.client.GeneratedTypeClient;
import com.manas.metaplatform.typesystem.dsl.FieldDecl;
import com.manas.metaplatform.typesystem.dsl.TypeDecl;
import com.manas.metaplatform.typesystem.web.TypeRegistry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The 2,000-query cross-client differential measurement behind the "the two generated clients
 * returned identical results on 2,000 generated queries" resume claim. Tagged {@code benchmark},
 * excluded from the default {@code mvn test} run for the same reason {@code BenchmarkRunner} is
 * (it starts a real server and shells out to Node); run explicitly with:
 * {@code mvn test -Dtest=TypePlatformBenchmarkRunner -Dsurefire.excludedGroups=}.
 *
 * <p>Seeds 10 real records per declared type (400 records across the 40 types) through the
 * generated Java clients, generates 2,000 queries against that seeded data (a GET-by-id or a
 * full LIST, type chosen uniformly at random), runs every query through the generated Java
 * clients in this JVM, then shells out to {@code node --experimental-strip-types
 * runtime/run-queries.ts} to run the identical 2,000 queries through the generated TypeScript
 * clients against the same live server, and diffs the two result arrays field for field. Both
 * sides talk to the same running server and the same underlying H2-in-PostgreSQL-compatibility
 * database, so a mismatch would mean the two generated clients (or the DDL/REST/model
 * generation feeding them) disagree, not that the data differs between runs.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TypePlatformBenchmarkRunner {

    private static final int QUERY_COUNT = 2000;
    private static final int RECORDS_PER_TYPE = 10;

    @LocalServerPort
    int port;

    @Autowired
    TypeRegistry registry;

    @Test
    @Tag("benchmark")
    void twoThousandQueriesAgreeAcrossGeneratedJavaAndTypeScriptClients() throws Exception {
        String baseUrl = "http://localhost:" + port;
        assertEquals(40, registry.size(), "expected 40 declared types");

        ObjectMapper mapper = new ObjectMapper();
        Random random = new Random(4242);
        List<TypeDecl> types = registry.all();

        Map<String, GeneratedTypeClient> javaClients = new LinkedHashMap<>();
        Map<String, List<Long>> idsByType = new LinkedHashMap<>();
        for (TypeDecl type : types) {
            GeneratedTypeClient client = TypePlatformClientLoader.load(type.name(), baseUrl);
            javaClients.put(type.name(), client);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < RECORDS_PER_TYPE; i++) {
                Map<String, Object> created = client.create(randomFields(type, random, i));
                ids.add(((Number) created.get("id")).longValue());
            }
            idsByType.put(type.name(), ids);
        }

        List<Map<String, Object>> queries = new ArrayList<>(QUERY_COUNT);
        for (int i = 0; i < QUERY_COUNT; i++) {
            TypeDecl type = types.get(random.nextInt(types.size()));
            boolean isGet = random.nextBoolean();
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("type", type.name());
            if (isGet) {
                query.put("op", "GET");
                List<Long> ids = idsByType.get(type.name());
                query.put("id", ids.get(random.nextInt(ids.size())));
            } else {
                query.put("op", "LIST");
            }
            queries.add(query);
        }

        Path queriesFile = Files.createTempFile("typeplatform-queries", ".json");
        Path tsResultsFile = Files.createTempFile("typeplatform-ts-results", ".json");
        mapper.writeValue(queriesFile.toFile(), queries);

        List<Object> javaResults = new ArrayList<>(QUERY_COUNT);
        for (Map<String, Object> query : queries) {
            GeneratedTypeClient client = javaClients.get((String) query.get("type"));
            if ("GET".equals(query.get("op"))) {
                javaResults.add(client.getById(((Number) query.get("id")).longValue()));
            } else {
                javaResults.add(client.list(Map.of()));
            }
        }

        Path tsRuntimeDir = Paths.get("generated-clients", "typescript").toAbsolutePath();
        ProcessBuilder processBuilder = new ProcessBuilder(
                "node", "--experimental-strip-types", "runtime/run-queries.ts",
                baseUrl, queriesFile.toAbsolutePath().toString(), tsResultsFile.toAbsolutePath().toString())
                .directory(tsRuntimeDir.toFile())
                .redirectErrorStream(true);
        Process process = processBuilder.start();
        String nodeOutput = new String(process.getInputStream().readAllBytes());
        boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("node run-queries.ts did not finish within 120s");
        }
        if (process.exitValue() != 0) {
            fail("node run-queries.ts exited " + process.exitValue() + ":\n" + nodeOutput);
        }

        @SuppressWarnings("unchecked")
        List<Object> tsResults = mapper.readValue(tsResultsFile.toFile(), List.class);
        assertEquals(queries.size(), tsResults.size(), "TypeScript client returned the wrong number of results");

        int matched = 0;
        List<Integer> mismatchedIndexes = new ArrayList<>();
        for (int i = 0; i < queries.size(); i++) {
            if (deepEquals(javaResults.get(i), tsResults.get(i))) {
                matched++;
            } else {
                mismatchedIndexes.add(i);
            }
        }

        String summary = matched + "/" + queries.size() + " generated queries matched between the generated "
                + "Java and TypeScript clients" + (mismatchedIndexes.isEmpty() ? "" : "; mismatched indexes: "
                + mismatchedIndexes.subList(0, Math.min(20, mismatchedIndexes.size())));
        System.out.println(summary);
        Files.createDirectories(Paths.get("docs"));
        Files.writeString(Paths.get("docs", "typeplatform_benchmark_output.txt"),
                "Type platform differential benchmark\n"
                        + "Declared types: " + registry.size() + "\n"
                        + "Seeded records: " + (types.size() * RECORDS_PER_TYPE) + " (" + RECORDS_PER_TYPE
                        + " per type across " + types.size() + " types)\n"
                        + "node runtime output:\n" + nodeOutput + "\n"
                        + "Result: " + summary + "\n");

        assertEquals(queries.size(), matched, summary);
    }

    /** Deep-equal that treats any two {@link Number}s as equal by value (Jackson can hand back
     * an {@code Integer} from one side and a {@code Double} from the other for the identical
     * JSON number, which is a serialization-format difference, not a correctness difference). */
    @SuppressWarnings("unchecked")
    private static boolean deepEquals(Object a, Object b) {
        if (a instanceof Map && b instanceof Map) {
            Map<Object, Object> ma = (Map<Object, Object>) a;
            Map<Object, Object> mb = (Map<Object, Object>) b;
            if (!ma.keySet().equals(mb.keySet())) {
                return false;
            }
            for (Object key : ma.keySet()) {
                if (!deepEquals(ma.get(key), mb.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a;
            List<Object> lb = (List<Object>) b;
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!deepEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number && b instanceof Number) {
            return Math.abs(((Number) a).doubleValue() - ((Number) b).doubleValue()) < 1e-9;
        }
        return java.util.Objects.equals(a, b);
    }

    private static Map<String, Object> randomFields(TypeDecl type, Random random, int seedIndex) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FieldDecl field : type.fields()) {
            fields.put(field.name(), switch (field.type()) {
                case STRING -> type.name() + "-" + seedIndex + "-" + field.name();
                case NUMBER -> Math.round(random.nextDouble() * 10000.0) / 100.0;
                case BOOLEAN -> random.nextBoolean();
            });
        }
        return fields;
    }
}
