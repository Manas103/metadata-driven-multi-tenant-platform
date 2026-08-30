package com.manas.metaplatform.dsl.ast;

import java.util.List;

/**
 * The record-level sharing predicate, injected into the WHERE tree by
 * {@code sharing.SharingScopingRewriter} the same way {@code compiler.TenantScopingRewriter}
 * injects {@link TenantScopeNode}: as a real AST node, ANDed at the root, before any SQL
 * text is produced. Carries everything the code generator needs to lower it directly
 * against the physical {@code records.owner_user_id} column and the {@code record_shares}
 * grant table, without going through the DSL field/join machinery (there is no DSL field
 * named "owner" in any tenant's catalog, and even if there were, this node type, not a field
 * name, is what the generator dispatches on).
 *
 * <p>{@code visibleOwnerUserIds} is the set of user ids whose owned records the viewer may
 * see through ownership or the role hierarchy (self plus every descendant role, so a manager
 * sees a subordinate's records but a peer or an unrelated branch does not), computed once
 * per query by {@code sharing.RoleHierarchyResolver}. A record owned by someone outside that
 * set is still visible if an explicit {@code record_shares} grant names the viewer, which is
 * why the generated predicate is an OR of both checks, never either one alone.
 */
public record SharingScopeNode(long viewerUserId, List<Long> visibleOwnerUserIds) implements Expr {
}
