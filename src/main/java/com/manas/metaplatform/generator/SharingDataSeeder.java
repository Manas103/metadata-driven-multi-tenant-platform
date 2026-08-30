package com.manas.metaplatform.generator;

import com.manas.metaplatform.domain.AppUser;
import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.FieldType;
import com.manas.metaplatform.domain.FieldValueEntity;
import com.manas.metaplatform.domain.ObjectDefinition;
import com.manas.metaplatform.domain.RecordEntity;
import com.manas.metaplatform.domain.RecordShare;
import com.manas.metaplatform.domain.Role;
import com.manas.metaplatform.domain.Tenant;
import com.manas.metaplatform.repository.AppUserRepository;
import com.manas.metaplatform.repository.FieldDefinitionRepository;
import com.manas.metaplatform.repository.FieldValueRepository;
import com.manas.metaplatform.repository.ObjectDefinitionRepository;
import com.manas.metaplatform.repository.RecordRepository;
import com.manas.metaplatform.repository.RecordShareRepository;
import com.manas.metaplatform.repository.RoleRepository;
import com.manas.metaplatform.repository.TenantRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds a deterministic, seeded dataset for the record-level sharing filter: one tenant,
 * a seven-level-wide, four-level-deep role hierarchy (CEO over two VPs, each over one
 * manager, each over five reps), one {@link AppUser} per role, a single "Opportunity"
 * custom object whose records are each owned by exactly one rep, and a handful of explicit
 * {@link RecordShare} grants to a user outside the owner's branch entirely, so ownership,
 * role-hierarchy visibility, and explicit sharing each have real, distinguishable cases in
 * the seeded data. Deliberately separate from {@link DataSeeder}: nothing here touches the
 * tenants, objects, or {@code Random} sequence the original tenant-isolation benchmark
 * depends on, so that benchmark's re-run is unaffected by this one existing.
 */
@Component
public final class SharingDataSeeder {

    private static final int RECORDS_PER_REP = 20;
    private static final int EXPLICIT_SHARE_COUNT = 40;
    private static final List<String> SALES_REP_ROLE_NAMES =
            List.of("Rep_Sales_1", "Rep_Sales_2", "Rep_Sales_3", "Rep_Sales_4", "Rep_Sales_5");
    private static final List<String> SUPPORT_REP_ROLE_NAMES =
            List.of("Rep_Support_1", "Rep_Support_2", "Rep_Support_3", "Rep_Support_4", "Rep_Support_5");

    private final TenantRepository tenantRepository;
    private final RoleRepository roleRepository;
    private final AppUserRepository appUserRepository;
    private final ObjectDefinitionRepository objectDefinitionRepository;
    private final FieldDefinitionRepository fieldDefinitionRepository;
    private final RecordRepository recordRepository;
    private final FieldValueRepository fieldValueRepository;
    private final RecordShareRepository recordShareRepository;

    public SharingDataSeeder(TenantRepository tenantRepository, RoleRepository roleRepository,
                              AppUserRepository appUserRepository,
                              ObjectDefinitionRepository objectDefinitionRepository,
                              FieldDefinitionRepository fieldDefinitionRepository,
                              RecordRepository recordRepository,
                              FieldValueRepository fieldValueRepository,
                              RecordShareRepository recordShareRepository) {
        this.tenantRepository = tenantRepository;
        this.roleRepository = roleRepository;
        this.appUserRepository = appUserRepository;
        this.objectDefinitionRepository = objectDefinitionRepository;
        this.fieldDefinitionRepository = fieldDefinitionRepository;
        this.recordRepository = recordRepository;
        this.fieldValueRepository = fieldValueRepository;
        this.recordShareRepository = recordShareRepository;
    }

    public SharingSeedDataset seed(long randomSeed) {
        Random random = new Random(randomSeed);

        Tenant tenant = tenantRepository.save(new Tenant("SharingCo"));
        long tenantId = tenant.getId();

        Map<String, Long> roleIdByName = new LinkedHashMap<>();
        Map<Long, Long> parentRoleIdByRoleId = new LinkedHashMap<>();

        long ceoRoleId = createRole(tenantId, "CEO", null, roleIdByName, parentRoleIdByRoleId);
        long vpSalesRoleId = createRole(tenantId, "VP_Sales", ceoRoleId, roleIdByName, parentRoleIdByRoleId);
        long vpSupportRoleId = createRole(tenantId, "VP_Support", ceoRoleId, roleIdByName, parentRoleIdByRoleId);
        long managerSalesRoleId =
                createRole(tenantId, "Manager_Sales", vpSalesRoleId, roleIdByName, parentRoleIdByRoleId);
        long managerSupportRoleId =
                createRole(tenantId, "Manager_Support", vpSupportRoleId, roleIdByName, parentRoleIdByRoleId);
        for (String repRoleName : SALES_REP_ROLE_NAMES) {
            createRole(tenantId, repRoleName, managerSalesRoleId, roleIdByName, parentRoleIdByRoleId);
        }
        for (String repRoleName : SUPPORT_REP_ROLE_NAMES) {
            createRole(tenantId, repRoleName, managerSupportRoleId, roleIdByName, parentRoleIdByRoleId);
        }

        Map<String, Long> userIdByRoleName = new LinkedHashMap<>();
        Map<Long, Long> roleIdByUserId = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : roleIdByName.entrySet()) {
            long userId = createUser(tenantId, entry.getKey() + "_user", entry.getValue());
            userIdByRoleName.put(entry.getKey(), userId);
            roleIdByUserId.put(userId, entry.getValue());
        }

        ObjectDefinition objectDefinition =
                objectDefinitionRepository.save(new ObjectDefinition(tenantId, "Opportunity"));
        long objectDefId = objectDefinition.getId();
        FieldDefinition codeField =
                fieldDefinitionRepository.save(new FieldDefinition(objectDefId, "code", FieldType.STRING));
        FieldDefinition amountField =
                fieldDefinitionRepository.save(new FieldDefinition(objectDefId, "amount", FieldType.NUMBER));
        Map<String, FieldDefinition> fieldDefsByName = new LinkedHashMap<>();
        fieldDefsByName.put("code", codeField);
        fieldDefsByName.put("amount", amountField);

        Map<Long, Long> ownerUserIdByRecordId = new LinkedHashMap<>();
        Map<Long, String> uniqueCodeByRecordId = new LinkedHashMap<>();
        List<Long> allRepRecordIds = new ArrayList<>();

        List<String> allRepRoleNames = new ArrayList<>();
        allRepRoleNames.addAll(SALES_REP_ROLE_NAMES);
        allRepRoleNames.addAll(SUPPORT_REP_ROLE_NAMES);

        for (String repRoleName : allRepRoleNames) {
            long ownerUserId = userIdByRoleName.get(repRoleName);
            for (int i = 0; i < RECORDS_PER_REP; i++) {
                RecordEntity record = recordRepository.save(new RecordEntity(tenantId, objectDefId, ownerUserId));
                long recordId = record.getId();
                String code = repRoleName + "-" + i + "-" + random.nextInt(1_000_000);
                BigDecimal amount = BigDecimal.valueOf(random.nextInt(500_000), 2);
                fieldValueRepository.save(FieldValueEntity.ofString(recordId, codeField.getId(), code));
                fieldValueRepository.save(FieldValueEntity.ofNumber(recordId, amountField.getId(), amount));
                ownerUserIdByRecordId.put(recordId, ownerUserId);
                uniqueCodeByRecordId.put(recordId, code);
                allRepRecordIds.add(recordId);
            }
        }

        // Explicit shares: each grants a record to a rep in the OTHER branch (Sales <-> Support),
        // who has no ownership and no role-hierarchy ancestor/descendant relationship to the
        // owner, so an explicit-share probe is genuinely testing the third visibility path, not
        // one already covered by ownership or the hierarchy.
        Map<Long, List<Long>> explicitlySharedWith = new LinkedHashMap<>();
        for (int i = 0; i < EXPLICIT_SHARE_COUNT; i++) {
            long recordId = allRepRecordIds.get(random.nextInt(allRepRecordIds.size()));
            long ownerUserId = ownerUserIdByRecordId.get(recordId);
            boolean ownerIsSales = isSalesOwner(ownerUserId, userIdByRoleName, SALES_REP_ROLE_NAMES);
            List<String> otherBranchRoleNames = ownerIsSales ? SUPPORT_REP_ROLE_NAMES : SALES_REP_ROLE_NAMES;
            String granteeRoleName = otherBranchRoleNames.get(random.nextInt(otherBranchRoleNames.size()));
            long granteeUserId = userIdByRoleName.get(granteeRoleName);
            recordShareRepository.save(new RecordShare(recordId, granteeUserId));
            explicitlySharedWith.computeIfAbsent(recordId, k -> new ArrayList<>()).add(granteeUserId);
        }

        return new SharingSeedDataset(tenantId, objectDefId, fieldDefsByName, roleIdByName, parentRoleIdByRoleId,
                userIdByRoleName, roleIdByUserId, ownerUserIdByRecordId, uniqueCodeByRecordId, explicitlySharedWith);
    }

    private boolean isSalesOwner(long ownerUserId, Map<String, Long> userIdByRoleName, List<String> salesRoleNames) {
        for (String salesRoleName : salesRoleNames) {
            if (userIdByRoleName.get(salesRoleName) == ownerUserId) {
                return true;
            }
        }
        return false;
    }

    private long createRole(long tenantId, String name, Long parentRoleId, Map<String, Long> roleIdByName,
                             Map<Long, Long> parentRoleIdByRoleId) {
        Role role = roleRepository.save(new Role(tenantId, name, parentRoleId));
        roleIdByName.put(name, role.getId());
        parentRoleIdByRoleId.put(role.getId(), parentRoleId);
        return role.getId();
    }

    private long createUser(long tenantId, String name, long roleId) {
        AppUser user = appUserRepository.save(new AppUser(tenantId, name, roleId));
        return user.getId();
    }
}
