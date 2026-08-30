package com.manas.metaplatform.generator;

import com.manas.metaplatform.domain.FieldDefinition;

import java.util.List;
import java.util.Map;

/**
 * Everything {@link SharingProbeGenerator} and {@link SharingQueryGenerator} need to build
 * sharing-visibility probes and reference-diff queries against a seeded role hierarchy,
 * without re-querying the database for the hierarchy shape on every generated probe.
 *
 * @param tenantId              the one tenant this sharing dataset lives in
 * @param objectDefId           the shared "Opportunity" custom object's definition id
 * @param fieldDefsByName       "code" (STRING, unique per record) and "amount" (NUMBER)
 * @param roleIdByName          e.g. "CEO", "VP_Sales", "Manager_Sales", "Rep_Sales_1", ...
 * @param parentRoleIdByRoleId  role id -> parent role id, null at the hierarchy's root
 * @param userIdByRoleName      one user per role, keyed by that role's name
 * @param roleIdByUserId        the inverse of userIdByRoleName, keyed by user id
 * @param ownerUserIdByRecordId every seeded record's owning user
 * @param uniqueCodeByRecordId  the unique "code" field value seeded on each record, used to
 *                              target one specific record with a WHERE clause
 * @param explicitlySharedWith  recordId -> the set of user ids explicitly granted access via
 *                              {@code record_shares}, independent of ownership or role
 */
public record SharingSeedDataset(
        long tenantId,
        long objectDefId,
        Map<String, FieldDefinition> fieldDefsByName,
        Map<String, Long> roleIdByName,
        Map<Long, Long> parentRoleIdByRoleId,
        Map<String, Long> userIdByRoleName,
        Map<Long, Long> roleIdByUserId,
        Map<Long, Long> ownerUserIdByRecordId,
        Map<Long, String> uniqueCodeByRecordId,
        Map<Long, List<Long>> explicitlySharedWith) {
}
