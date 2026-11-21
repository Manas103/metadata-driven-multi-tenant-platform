package com.manas.metaplatform.admin;

import com.manas.metaplatform.compiler.CompiledSql;
import com.manas.metaplatform.compiler.QueryExecutor;
import com.manas.metaplatform.domain.AppUser;
import com.manas.metaplatform.domain.FieldDefinition;
import com.manas.metaplatform.domain.FieldType;
import com.manas.metaplatform.domain.FieldValueEntity;
import com.manas.metaplatform.domain.ObjectDefinition;
import com.manas.metaplatform.domain.RecordEntity;
import com.manas.metaplatform.domain.Role;
import com.manas.metaplatform.domain.Tenant;
import com.manas.metaplatform.repository.AppUserRepository;
import com.manas.metaplatform.repository.FieldDefinitionRepository;
import com.manas.metaplatform.repository.FieldValueRepository;
import com.manas.metaplatform.repository.ObjectDefinitionRepository;
import com.manas.metaplatform.repository.RecordRepository;
import com.manas.metaplatform.repository.RoleRepository;
import com.manas.metaplatform.repository.TenantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The admin API behind the React console: tenants, their metadata-defined objects and
 * fields, records, and the query console. Every read of record data goes through
 * {@link QueryExecutor}, so the console exercises the same tenant (and, with a viewer,
 * sharing) scoping as everything else; nothing here builds SQL from request text.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final TenantRepository tenants;
    private final ObjectDefinitionRepository objects;
    private final FieldDefinitionRepository fields;
    private final RecordRepository records;
    private final FieldValueRepository fieldValues;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final QueryExecutor queryExecutor;
    private final JdbcTemplate jdbc;

    public AdminController(TenantRepository tenants, ObjectDefinitionRepository objects,
                           FieldDefinitionRepository fields, RecordRepository records,
                           FieldValueRepository fieldValues, AppUserRepository users, RoleRepository roles,
                           QueryExecutor queryExecutor, JdbcTemplate jdbc) {
        this.tenants = tenants;
        this.objects = objects;
        this.fields = fields;
        this.records = records;
        this.fieldValues = fieldValues;
        this.users = users;
        this.roles = roles;
        this.queryExecutor = queryExecutor;
        this.jdbc = jdbc;
    }

    public record TenantView(long id, String name, int objectCount, int userCount) {}

    public record FieldView(long id, String name, FieldType type) {}

    public record ObjectView(long id, String name, List<FieldView> fields, long recordCount) {}

    public record UserView(long id, String name, String role) {}

    public record NewObject(String name) {}

    public record NewField(String name, FieldType type) {}

    public record NewRecord(Map<String, Object> values, Long ownerUserId) {}

    public record QueryRequest(String dsl, Long viewerUserId) {}

    public record QueryResponse(String scoping, String sql, List<Object> params,
                                List<Map<String, Object>> rows, double elapsedMs) {}

    @GetMapping("/tenants")
    public List<TenantView> tenants() {
        return tenants.findAll().stream()
                .sorted(Comparator.comparing(Tenant::getId))
                .map(t -> new TenantView(t.getId(), t.getName(),
                        objects.findByTenantId(t.getId()).size(), users.findByTenantId(t.getId()).size()))
                .toList();
    }

    @GetMapping("/tenants/{tenantId}/objects")
    public List<ObjectView> objects(@PathVariable long tenantId) {
        requireTenant(tenantId);
        return objects.findByTenantId(tenantId).stream()
                .sorted(Comparator.comparing(ObjectDefinition::getName))
                .map(this::view)
                .toList();
    }

    /** A new object is one metadata row: no CREATE TABLE. */
    @PostMapping("/tenants/{tenantId}/objects")
    @Transactional
    public ObjectView createObject(@PathVariable long tenantId, @RequestBody NewObject body) {
        requireTenant(tenantId);
        String name = requireIdentifier(body.name(), "object");
        if (objects.findByTenantIdAndName(tenantId, name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "object " + name + " already exists");
        }
        return view(objects.save(new ObjectDefinition(tenantId, name)));
    }

    /** A new field is one metadata row: no ALTER TABLE. Existing records read it as null. */
    @PostMapping("/tenants/{tenantId}/objects/{objectName}/fields")
    @Transactional
    public ObjectView addField(@PathVariable long tenantId, @PathVariable String objectName,
                               @RequestBody NewField body) {
        ObjectDefinition od = requireObject(tenantId, objectName);
        String name = requireIdentifier(body.name(), "field");
        if (body.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "field type is required");
        }
        if (fields.findByObjectDefinitionIdAndName(od.getId(), name).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "field " + name + " already exists");
        }
        fields.save(new FieldDefinition(od.getId(), name, body.type()));
        return view(od);
    }

    @PostMapping("/tenants/{tenantId}/objects/{objectName}/records")
    @Transactional
    public Map<String, Object> createRecord(@PathVariable long tenantId, @PathVariable String objectName,
                                            @RequestBody NewRecord body) {
        ObjectDefinition od = requireObject(tenantId, objectName);
        Long ownerId = body.ownerUserId();
        if (ownerId != null && users.findById(ownerId).filter(u -> u.getTenantId() == tenantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "owner " + ownerId + " is not a user of this tenant");
        }
        Map<String, FieldDefinition> byName = new HashMap<>();
        for (FieldDefinition fd : fields.findByObjectDefinitionId(od.getId())) {
            byName.put(fd.getName(), fd);
        }
        Map<String, Object> values = body.values() == null ? Map.of() : body.values();
        for (String key : values.keySet()) {
            if (!byName.containsKey(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, objectName + " has no field " + key);
            }
        }
        RecordEntity record = records.save(new RecordEntity(tenantId, od.getId(), ownerId));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("record_id", record.getId());
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            FieldDefinition fd = byName.get(e.getKey());
            fieldValues.save(switch (fd.getFieldType()) {
                case STRING -> FieldValueEntity.ofString(record.getId(), fd.getId(), String.valueOf(e.getValue()));
                case NUMBER -> FieldValueEntity.ofNumber(record.getId(), fd.getId(), number(e.getKey(), e.getValue()));
                case BOOLEAN -> FieldValueEntity.ofBoolean(record.getId(), fd.getId(), bool(e.getKey(), e.getValue()));
            });
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    @GetMapping("/tenants/{tenantId}/users")
    public List<UserView> users(@PathVariable long tenantId) {
        requireTenant(tenantId);
        Map<Long, String> roleNames = new HashMap<>();
        for (Role r : roles.findByTenantId(tenantId)) {
            roleNames.put(r.getId(), r.getName());
        }
        return users.findByTenantId(tenantId).stream()
                .sorted(Comparator.comparing(AppUser::getId))
                .map(u -> new UserView(u.getId(), u.getName(), roleNames.get(u.getRoleId())))
                .toList();
    }

    /**
     * Runs a DSL query for one tenant. With a viewer, the record-level sharing filter is
     * injected too. The response carries the compiled SQL so the console can show where the
     * scoping predicates landed.
     */
    @PostMapping("/tenants/{tenantId}/query")
    public QueryResponse query(@PathVariable long tenantId, @RequestBody QueryRequest body) {
        requireTenant(tenantId);
        if (body.dsl() == null || body.dsl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "dsl is required");
        }
        long start = System.nanoTime();
        CompiledSql compiled;
        List<Map<String, Object>> rows;
        String scoping;
        if (body.viewerUserId() != null) {
            users.findById(body.viewerUserId())
                    .filter(u -> u.getTenantId() == tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "viewer " + body.viewerUserId() + " is not a user of this tenant"));
            compiled = queryExecutor.compileOnlyWithSharing(body.dsl(), tenantId, body.viewerUserId());
            rows = queryExecutor.executeWithSharing(body.dsl(), tenantId, body.viewerUserId());
            scoping = "tenant+sharing";
        } else {
            compiled = queryExecutor.compileOnly(body.dsl(), tenantId);
            rows = queryExecutor.execute(body.dsl(), tenantId);
            scoping = "tenant";
        }
        double elapsedMs = (System.nanoTime() - start) / 1e6;
        return new QueryResponse(scoping, compiled.sql(), new ArrayList<>(compiled.params()), rows, elapsedMs);
    }

    private ObjectView view(ObjectDefinition od) {
        List<FieldView> fv = fields.findByObjectDefinitionId(od.getId()).stream()
                .sorted(Comparator.comparing(FieldDefinition::getId))
                .map(f -> new FieldView(f.getId(), f.getName(), f.getFieldType()))
                .toList();
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM records WHERE tenant_id = ? AND object_definition_id = ?",
                Long.class, od.getTenantId(), od.getId());
        return new ObjectView(od.getId(), od.getName(), fv, count == null ? 0 : count);
    }

    private void requireTenant(long tenantId) {
        if (!tenants.existsById(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no tenant " + tenantId);
        }
    }

    private ObjectDefinition requireObject(long tenantId, String objectName) {
        requireTenant(tenantId);
        return objects.findByTenantIdAndName(tenantId, objectName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "tenant " + tenantId + " has no object " + objectName));
    }

    private static String requireIdentifier(String name, String what) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,62}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    what + " name must be a letter followed by up to 62 letters, digits or underscores");
        }
        return name;
    }

    private static BigDecimal number(String field, Object v) {
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be a number");
        }
    }

    private static Boolean bool(String field, Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v);
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return Boolean.valueOf(s);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be true or false");
    }
}
