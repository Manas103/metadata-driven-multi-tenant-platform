package com.manas.metaplatform.generator;

/**
 * One sharing-visibility probe: a query issued as {@code viewerUserId} ({@code viewerLabel})
 * against the sharing dataset's shared object, targeted (via a unique field match) at one
 * specific {@code targetRecordId}, together with the ground-truth {@code expectedVisible}
 * computed independently from the seeded ownership/role-hierarchy/explicit-share data.
 * {@code category} names the exact relationship under test (owner, role hierarchy, explicit
 * share, sibling, cross-branch), so a failure in the adversarial suite points straight at
 * which visibility path broke.
 */
public record SharingProbe(long viewerUserId, String viewerLabel, long targetRecordId, String dsl,
                            String category, String description, boolean expectedVisible) {
}
