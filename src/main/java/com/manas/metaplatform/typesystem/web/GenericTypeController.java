package com.manas.metaplatform.typesystem.web;

import com.manas.metaplatform.domain.FieldType;
import com.manas.metaplatform.typesystem.compiler.NamingConventions;
import com.manas.metaplatform.typesystem.dsl.FieldDecl;
import com.manas.metaplatform.typesystem.dsl.TypeDecl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The REST surface every generated Java and TypeScript client talks to: one generic
 * controller, backed by the real per-type tables {@link TypeSystemBootstrap} created from
 * the declared DDL, rather than 40 hand-written controllers. This is deliberately generic
 * (dispatching on the {@code typeName} path variable at request time) rather than
 * per-type-generated, the same tradeoff most real ORM-backed admin APIs make: the client
 * side is where "typed" earns its keep, because that is the code an application author
 * actually writes against.
 */
@RestController
@RequestMapping("/api/types")
public class GenericTypeController {

    private final JdbcTemplate jdbcTemplate;
    private final TypeRegistry registry;

    public GenericTypeController(JdbcTemplate jdbcTemplate, TypeRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
    }

    @GetMapping
    public List<Map<String, Object>> listDeclaredTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TypeDecl type : registry.all()) {
            List<Map<String, String>> fields = new ArrayList<>();
            for (FieldDecl field : type.fields()) {
                fields.add(Map.of("name", field.name(), "type", field.type().name()));
            }
            out.add(Map.of("name", type.name(), "fields", fields));
        }
        return out;
    }

    @PostMapping("/{typeName}")
    public Map<String, Object> create(@PathVariable String typeName, @RequestBody Map<String, Object> body) {
        TypeDecl type = registry.require(typeName);
        String table = NamingConventions.tableName(type.name());
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (FieldDecl field : type.fields()) {
            columns.add(NamingConventions.columnName(field.name()));
            values.add(coerceForStorage(field.type(), body.get(field.name())));
        }
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> "?").toList()) + ")";

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.size(); i++) {
                ps.setObject(i + 1, values.get(i));
            }
            return ps;
        }, keyHolder);

        long id = keyHolder.getKey().longValue();
        return getById(typeName, id);
    }

    @GetMapping("/{typeName}/{id}")
    public Map<String, Object> getById(@PathVariable String typeName, @PathVariable long id) {
        TypeDecl type = registry.require(typeName);
        String table = NamingConventions.tableName(type.name());
        String sql = "SELECT * FROM " + table + " WHERE id = ?";
        List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rowToMap(rs, type), id);
        if (rows.isEmpty()) {
            throw new RecordNotFoundException(typeName, id);
        }
        return rows.get(0);
    }

    @GetMapping("/{typeName}")
    public List<Map<String, Object>> list(@PathVariable String typeName,
                                           @RequestParam(required = false) Map<String, String> filters) {
        TypeDecl type = registry.require(typeName);
        String table = NamingConventions.tableName(type.name());
        Map<String, FieldDecl> fieldsByName = new LinkedHashMap<>();
        for (FieldDecl field : type.fields()) {
            fieldsByName.put(field.name(), field);
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table);
        List<Object> params = new ArrayList<>();
        if (filters != null && !filters.isEmpty()) {
            List<String> clauses = new ArrayList<>();
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                FieldDecl field = fieldsByName.get(entry.getKey());
                if (field == null) {
                    throw new IllegalArgumentException(
                            "Type " + typeName + " has no field named '" + entry.getKey() + "'");
                }
                clauses.add(NamingConventions.columnName(field.name()) + " = ?");
                params.add(coerceForStorage(field.type(), entry.getValue()));
            }
            sql.append(" WHERE ").append(String.join(" AND ", clauses));
        }
        sql.append(" ORDER BY id");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> rowToMap(rs, type), params.toArray());
    }

    private Object coerceForStorage(FieldType fieldType, Object rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("Missing required value for a " + fieldType + " field");
        }
        return switch (fieldType) {
            case STRING -> rawValue.toString();
            case NUMBER -> rawValue instanceof Number n ? n.doubleValue() : Double.parseDouble(rawValue.toString());
            case BOOLEAN -> rawValue instanceof Boolean b ? b : Boolean.parseBoolean(rawValue.toString());
        };
    }

    private Map<String, Object> rowToMap(ResultSet rs, TypeDecl type) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        for (FieldDecl field : type.fields()) {
            String column = NamingConventions.columnName(field.name());
            Object value = switch (field.type()) {
                case STRING -> rs.getString(column);
                case NUMBER -> rs.getDouble(column);
                case BOOLEAN -> rs.getBoolean(column);
            };
            row.put(field.name(), value);
        }
        return row;
    }
}
