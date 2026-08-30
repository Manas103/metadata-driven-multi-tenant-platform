package com.manas.metaplatform.generator;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates syntactically valid, randomly shaped DSL queries over the sharing dataset's shared
 * "Opportunity" object, each paired with a randomly chosen viewer (any seeded user, not just
 * reps, so CEO and VP viewers with a wide {@code visibleOwnerUserIds} set are exercised too).
 * This is the corpus the sharing-filter reference-translator diff run is driven from, the
 * sharing-side equivalent of {@link QueryGenerator}.
 */
@Component
public final class SharingQueryGenerator {

    public List<GeneratedSharingQuery> generate(SharingSeedDataset dataset, int count, long seed) {
        Random random = new Random(seed);
        List<String> allUserLabels = List.copyOf(dataset.userIdByRoleName().keySet());
        List<GeneratedSharingQuery> queries = new ArrayList<>(count);
        List<String> codes = List.copyOf(dataset.uniqueCodeByRecordId().values());

        for (int i = 0; i < count; i++) {
            String viewerRoleName = allUserLabels.get(random.nextInt(allUserLabels.size()));
            long viewerUserId = dataset.userIdByRoleName().get(viewerRoleName);

            List<String> selectFields = random.nextBoolean() ? List.of("code") : List.of("code", "amount");
            String where = buildWhere(random, codes);
            String dsl = "SELECT " + String.join(", ", selectFields) + " FROM Opportunity"
                    + (where.isEmpty() ? "" : " WHERE " + where);
            queries.add(new GeneratedSharingQuery(dataset.tenantId(), viewerUserId, viewerRoleName, dsl));
        }
        return queries;
    }

    private String buildWhere(Random random, List<String> codes) {
        int shape = random.nextInt(3); // 0: no WHERE, 1: amount comparison, 2: code equality
        return switch (shape) {
            case 0 -> "";
            case 1 -> "amount >= " + BigDecimal.valueOf(random.nextInt(500_000), 2);
            default -> "code = '" + codes.get(random.nextInt(codes.size())).replace("'", "''") + "'";
        };
    }
}
