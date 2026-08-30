package com.manas.metaplatform.generator;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds adversarial record-level sharing-visibility probes against a {@link SharingSeedDataset}.
 * Each probe targets exactly one record by its unique {@code code} field (so at most one row can
 * ever come back) and names, independently of anything the sharing filter itself computes, whether
 * the viewer is expected to see it. Five categories, each a genuinely different visibility path so
 * a bug in one path cannot hide behind another path's correct answer:
 *
 * <ul>
 *   <li><b>owner</b>: the viewer owns the target record. Expected visible.</li>
 *   <li><b>ancestor</b>: the viewer's role is a strict ancestor of the owner's role in the
 *       hierarchy (manager, VP, or CEO). Expected visible.</li>
 *   <li><b>explicit_share</b>: the viewer has no ownership or hierarchy relationship to the
 *       record at all, but is named in {@code record_shares}. Expected visible.</li>
 *   <li><b>sibling_denied</b>: the viewer holds the same rep role tier as the owner (a peer,
 *       not an ancestor, not the owner, not an explicit grantee for this record). Expected
 *       NOT visible; this is the adversarial case a same-branch-visibility bug would fail.</li>
 *   <li><b>cross_branch_denied</b>: the viewer sits in the other branch entirely (Sales vs.
 *       Support) with no relationship to the record. Expected NOT visible.</li>
 * </ul>
 */
@Component
public final class SharingProbeGenerator {

    private static final List<String> SALES_REP_ROLE_NAMES =
            List.of("Rep_Sales_1", "Rep_Sales_2", "Rep_Sales_3", "Rep_Sales_4", "Rep_Sales_5");
    private static final List<String> SUPPORT_REP_ROLE_NAMES =
            List.of("Rep_Support_1", "Rep_Support_2", "Rep_Support_3", "Rep_Support_4", "Rep_Support_5");
    private static final List<String> CATEGORIES =
            List.of("owner", "ancestor", "explicit_share", "sibling_denied", "cross_branch_denied");

    public List<SharingProbe> generate(SharingSeedDataset dataset, int count, long seed) {
        Random random = new Random(seed);
        List<Long> allRecordIds = List.copyOf(dataset.ownerUserIdByRecordId().keySet());
        List<SharingProbe> probes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String category = CATEGORIES.get(i % CATEGORIES.size());
            long targetRecordId = allRecordIds.get(random.nextInt(allRecordIds.size()));
            SharingProbe probe = buildProbe(dataset, category, targetRecordId, random, allRecordIds);
            probes.add(probe);
        }
        return probes;
    }

    private SharingProbe buildProbe(SharingSeedDataset dataset, String category, long targetRecordId,
                                     Random random, List<Long> allRecordIds) {
        long ownerUserId = dataset.ownerUserIdByRecordId().get(targetRecordId);
        String code = dataset.uniqueCodeByRecordId().get(targetRecordId);
        String dsl = "SELECT code FROM Opportunity WHERE code = '" + code.replace("'", "''") + "'";

        long ownerRoleId = dataset.roleIdByUserId().get(ownerUserId);
        String ownerRoleName = roleNameById(dataset, ownerRoleId);
        boolean ownerIsSales = SALES_REP_ROLE_NAMES.contains(ownerRoleName);

        return switch (category) {
            case "owner" -> new SharingProbe(ownerUserId, ownerRoleName + "_user", targetRecordId, dsl,
                    category, "owner viewing their own record", true);
            case "ancestor" -> {
                List<String> ancestorChain = ownerIsSales
                        ? List.of("Manager_Sales", "VP_Sales", "CEO")
                        : List.of("Manager_Support", "VP_Support", "CEO");
                String ancestorRoleName = ancestorChain.get(random.nextInt(ancestorChain.size()));
                long viewerUserId = dataset.userIdByRoleName().get(ancestorRoleName);
                yield new SharingProbe(viewerUserId, ancestorRoleName + "_user", targetRecordId, dsl,
                        category, ancestorRoleName + " viewing a descendant's record through the role hierarchy",
                        true);
            }
            case "explicit_share" -> {
                List<Long> grantees = dataset.explicitlySharedWith().getOrDefault(targetRecordId, List.of());
                if (grantees.isEmpty()) {
                    // No explicit grant landed on this record; fall back to a genuine explicit
                    // grantee of a different record but still probe against THIS target, so the
                    // expectation is correctly "not visible" (no ownership/hierarchy/grant path
                    // reaches this record for that viewer either), which the denial categories
                    // already cover. Redirect instead to a record that does have a grant.
                    yield explicitShareProbeOnGrantedRecord(dataset, random);
                }
                long viewerUserId = grantees.get(random.nextInt(grantees.size()));
                yield new SharingProbe(viewerUserId, "grantee_user_" + viewerUserId, targetRecordId, dsl,
                        category, "explicit record_shares grantee outside ownership and the role hierarchy",
                        true);
            }
            case "sibling_denied" -> {
                List<String> peerTier = ownerIsSales ? SALES_REP_ROLE_NAMES : SUPPORT_REP_ROLE_NAMES;
                String peerRoleName;
                do {
                    peerRoleName = peerTier.get(random.nextInt(peerTier.size()));
                } while (peerRoleName.equals(ownerRoleName));
                long viewerUserId = dataset.userIdByRoleName().get(peerRoleName);
                boolean explicitlyGranted = dataset.explicitlySharedWith()
                        .getOrDefault(targetRecordId, List.of()).contains(viewerUserId);
                yield new SharingProbe(viewerUserId, peerRoleName + "_user", targetRecordId, dsl,
                        category, "same-tier peer rep with no ownership, hierarchy, or grant relationship",
                        explicitlyGranted);
            }
            case "cross_branch_denied" -> {
                List<String> otherBranch = ownerIsSales ? SUPPORT_REP_ROLE_NAMES : SALES_REP_ROLE_NAMES;
                String viewerRoleName = otherBranch.get(random.nextInt(otherBranch.size()));
                long viewerUserId = dataset.userIdByRoleName().get(viewerRoleName);
                boolean explicitlyGranted = dataset.explicitlySharedWith()
                        .getOrDefault(targetRecordId, List.of()).contains(viewerUserId);
                yield new SharingProbe(viewerUserId, viewerRoleName + "_user", targetRecordId, dsl,
                        category, "unrelated branch entirely, no ownership, hierarchy, or grant relationship",
                        explicitlyGranted);
            }
            default -> throw new IllegalStateException("unknown category " + category);
        };
    }

    private SharingProbe explicitShareProbeOnGrantedRecord(SharingSeedDataset dataset, Random random) {
        List<Long> grantedRecordIds = List.copyOf(dataset.explicitlySharedWith().keySet());
        long recordId = grantedRecordIds.get(random.nextInt(grantedRecordIds.size()));
        List<Long> grantees = dataset.explicitlySharedWith().get(recordId);
        long viewerUserId = grantees.get(random.nextInt(grantees.size()));
        String code = dataset.uniqueCodeByRecordId().get(recordId);
        String dsl = "SELECT code FROM Opportunity WHERE code = '" + code.replace("'", "''") + "'";
        return new SharingProbe(viewerUserId, "grantee_user_" + viewerUserId, recordId, dsl,
                "explicit_share", "explicit record_shares grantee outside ownership and the role hierarchy",
                true);
    }

    private String roleNameById(SharingSeedDataset dataset, long roleId) {
        for (Map.Entry<String, Long> entry : dataset.roleIdByName().entrySet()) {
            if (entry.getValue() == roleId) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("unknown role id " + roleId);
    }
}
