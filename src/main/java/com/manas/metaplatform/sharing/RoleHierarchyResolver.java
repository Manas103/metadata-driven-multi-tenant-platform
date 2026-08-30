package com.manas.metaplatform.sharing;

import com.manas.metaplatform.domain.AppUser;
import com.manas.metaplatform.domain.Role;
import com.manas.metaplatform.repository.AppUserRepository;
import com.manas.metaplatform.repository.RoleRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes, for a tenant and a viewing user, every user id whose owned records that viewer
 * is entitled to see through the role hierarchy: the viewer's own id, plus every user whose
 * role is a descendant of the viewer's role (a manager sees a subordinate's records; a peer
 * or an unrelated branch does not, and neither does an ancestor's role reach back down past
 * its own subtree in the other direction). The result is computed once per query, in Java,
 * over the small in-memory role/user graph for the tenant, by breadth-first descent from the
 * viewer's role, never a recursive SQL CTE, then handed to {@link SharingScopingRewriter} to
 * inject as a real AST node ({@link com.manas.metaplatform.dsl.ast.SharingScopeNode}) rather
 * than a subquery string.
 */
@Component
public final class RoleHierarchyResolver {

    private final RoleRepository roleRepository;
    private final AppUserRepository appUserRepository;

    public RoleHierarchyResolver(RoleRepository roleRepository, AppUserRepository appUserRepository) {
        this.roleRepository = roleRepository;
        this.appUserRepository = appUserRepository;
    }

    public List<Long> visibleOwnerUserIds(long tenantId, long viewerUserId) {
        AppUser viewer = appUserRepository.findById(viewerUserId)
                .orElseThrow(() -> new IllegalArgumentException("unknown user id " + viewerUserId));

        List<Role> roles = roleRepository.findByTenantId(tenantId);
        Map<Long, List<Long>> childRoleIdsByParent = new LinkedHashMap<>();
        for (Role role : roles) {
            if (role.getParentRoleId() != null) {
                childRoleIdsByParent.computeIfAbsent(role.getParentRoleId(), k -> new ArrayList<>()).add(role.getId());
            }
        }

        Set<Long> visibleRoleIds = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(viewer.getRoleId());
        while (!queue.isEmpty()) {
            long roleId = queue.poll();
            if (!visibleRoleIds.add(roleId)) {
                continue; // already visited: the role graph is a tree here, but guard cycles anyway
            }
            for (Long childRoleId : childRoleIdsByParent.getOrDefault(roleId, List.of())) {
                queue.add(childRoleId);
            }
        }

        List<Long> visibleOwnerUserIds = new ArrayList<>();
        for (AppUser user : appUserRepository.findByTenantId(tenantId)) {
            if (visibleRoleIds.contains(user.getRoleId())) {
                visibleOwnerUserIds.add(user.getId());
            }
        }
        return visibleOwnerUserIds;
    }
}
