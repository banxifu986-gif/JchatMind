package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class MigrationCatalogContract {

    public record CatalogObject(
            String kind,
            String schema,
            String table,
            String name,
            String qualifier
    ) {

        public CatalogObject {
            kind = requireText(kind, "kind");
            schema = schema == null ? "" : schema;
            table = table == null ? "" : table;
            name = requireText(name, "name");
            qualifier = qualifier == null ? "" : qualifier;
        }

        public String canonicalName() {
            return String.join(":", kind, schema, table, name, qualifier);
        }

        private static String requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            return value;
        }
    }

    private final String contractVersion;
    private final String schema;
    private final Path sourcePath;
    private final String sha256;
    private final Set<CatalogObject> requiredObjects;
    private final Set<CatalogObject> forbiddenObjects;
    private final Set<CatalogObject> allowedObjects;
    private final Set<CatalogObject> requiredDefinitionObjects;

    private MigrationCatalogContract(
            String contractVersion,
            String schema,
            Path sourcePath,
            String sha256,
            Set<CatalogObject> requiredObjects,
            Set<CatalogObject> forbiddenObjects,
            Set<CatalogObject> allowedObjects,
            Set<CatalogObject> requiredDefinitionObjects
    ) {
        this.contractVersion = contractVersion;
        this.schema = schema;
        this.sourcePath = sourcePath;
        this.sha256 = sha256;
        this.requiredObjects = Set.copyOf(requiredObjects);
        this.forbiddenObjects = Set.copyOf(forbiddenObjects);
        this.allowedObjects = Set.copyOf(allowedObjects);
        this.requiredDefinitionObjects = Set.copyOf(requiredDefinitionObjects);
    }

    public static MigrationCatalogContract load(Path path) {
        Objects.requireNonNull(path, "path");
        Path sourcePath = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalStateException("Migration catalog contract is missing: " + sourcePath);
        }
        try {
            byte[] content = Files.readAllBytes(sourcePath);
            JsonNode root = new ObjectMapper().readTree(content);
            String contractVersion = text(root, "contractVersion");
            String schema = text(root, "schema");
            if (!"1".equals(contractVersion) || root == null || !root.isObject()) {
                throw new IllegalStateException("Migration catalog contract is incomplete");
            }

            Set<CatalogObject> required = new LinkedHashSet<>();
            for (JsonNode extension : requiredArray(root, "requiredExtensions")) {
                required.add(new CatalogObject("extension", "", "", text(extension), ""));
            }
            for (JsonNode table : requiredArray(root, "requiredTables")) {
                required.add(new CatalogObject("table", schema, "", text(table), ""));
            }
            readColumns(root, schema, "requiredColumns", required);
            readConstraints(root, schema, required);
            readIndexes(root, schema, required);
            for (JsonNode function : requiredArray(root, "requiredFunctions")) {
                required.add(new CatalogObject("function", schema, "", text(function), ""));
            }
            for (JsonNode trigger : requiredArray(root, "requiredTriggers")) {
                required.add(new CatalogObject(
                        "trigger", schema, text(trigger, "table"), text(trigger, "name"), ""
                ));
            }

            Set<CatalogObject> forbidden = new LinkedHashSet<>();
            JsonNode forbiddenColumns = root.path("forbiddenColumns");
            if (!forbiddenColumns.isObject()) {
                throw new IllegalStateException("Migration catalog contract has invalid forbiddenColumns");
            }
            forbiddenColumns.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isArray()) {
                    throw new IllegalStateException(
                            "Migration catalog contract field " + entry.getKey() + " must be an array"
                    );
                }
                for (JsonNode column : entry.getValue()) {
                    forbidden.add(new CatalogObject("column", schema, entry.getKey(), text(column), ""));
                }
            });

            Set<CatalogObject> allowed = new LinkedHashSet<>();
            for (JsonNode extension : requiredArray(root, "allowedExtensions")) {
                allowed.add(new CatalogObject("extension", "", "", text(extension), ""));
            }
            for (JsonNode table : requiredArray(root, "allowedTables")) {
                allowed.add(new CatalogObject("table", schema, "", text(table), ""));
            }
            readColumns(root, schema, "allowedColumns", allowed);
            readConstraints(root, schema, "allowedConstraints", allowed);
            readIndexes(root, schema, "allowedIndexes", allowed);
            for (JsonNode function : requiredArray(root, "allowedFunctions")) {
                allowed.add(new CatalogObject("function", schema, "", text(function), ""));
            }
            for (JsonNode trigger : requiredArray(root, "allowedTriggers")) {
                allowed.add(new CatalogObject(
                        "trigger", schema, text(trigger, "table"), text(trigger, "name"), ""
                ));
            }
            allowed.addAll(required);

            Set<CatalogObject> requiredDefinitions = new LinkedHashSet<>();
            for (JsonNode definition : requiredArray(root, "requiredDefinitions")) {
                String matchType = text(definition, "matchType");
                if (!"exact".equals(matchType) && !"contains".equals(matchType)) {
                    throw new IllegalStateException("Migration catalog definition has an invalid matchType");
                }
                String table = optionalText(definition, "table");
                requiredDefinitions.add(new CatalogObject(
                        definitionKind(text(definition, "kind")),
                        schema,
                        table,
                        text(definition, "name"),
                        matchType + ":" + textAllowEmpty(definition, "match")
                ));
            }

            if (required.isEmpty() || required.stream().anyMatch(object -> !object.schema().isEmpty()
                    && !schema.equals(object.schema()))) {
                throw new IllegalStateException("Migration catalog contract has no usable required objects");
            }
            return new MigrationCatalogContract(
                    contractVersion,
                    schema,
                    sourcePath,
                    sha256(content),
                    required,
                    forbidden,
                    allowed,
                    requiredDefinitions
            );
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration catalog contract", e);
        }
    }

    public String contractVersion() {
        return contractVersion;
    }

    public String schema() {
        return schema;
    }

    public Path sourcePath() {
        return sourcePath;
    }

    public String sha256() {
        return sha256;
    }

    public Set<CatalogObject> requiredObjects() {
        return requiredObjects;
    }

    public Set<CatalogObject> forbiddenObjects() {
        return forbiddenObjects;
    }

    public Set<CatalogObject> allowedObjects() {
        return allowedObjects;
    }

    public Set<CatalogObject> requiredDefinitionObjects() {
        return requiredDefinitionObjects;
    }

    private static void readColumns(JsonNode root, String schema, String field, Set<CatalogObject> target) {
        JsonNode columns = root.path(field);
        if (!columns.isObject()) {
            throw new IllegalStateException("Migration catalog contract has invalid " + field);
        }
        columns.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isArray()) {
                throw new IllegalStateException(
                        "Migration catalog contract field " + entry.getKey() + " must be an array"
                );
            }
            for (JsonNode column : entry.getValue()) {
                target.add(new CatalogObject("column", schema, entry.getKey(), text(column), ""));
            }
        });
    }

    private static void readConstraints(JsonNode root, String schema, Set<CatalogObject> target) {
        readConstraints(root, schema, "requiredConstraints", target);
    }

    private static void readConstraints(
            JsonNode root,
            String schema,
            String field,
            Set<CatalogObject> target
    ) {
        for (JsonNode constraint : requiredArray(root, field)) {
            target.add(new CatalogObject(
                    "constraint",
                    schema,
                    text(constraint, "table"),
                    text(constraint, "name"),
                    text(constraint, "type") + ":" + booleanText(constraint, "validated")
            ));
        }
    }

    private static void readIndexes(JsonNode root, String schema, Set<CatalogObject> target) {
        readIndexes(root, schema, "requiredIndexes", target);
    }

    private static void readIndexes(
            JsonNode root,
            String schema,
            String field,
            Set<CatalogObject> target
    ) {
        for (JsonNode index : requiredArray(root, field)) {
            target.add(new CatalogObject(
                    "index",
                    schema,
                    text(index, "table"),
                    text(index, "name"),
                    text(index, "method") + ":" + booleanText(index, "unique")
            ));
        }
    }

    private static List<JsonNode> requiredArray(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) {
            throw new IllegalStateException("Migration catalog contract has invalid " + field);
        }
        List<JsonNode> nodes = new ArrayList<>();
        value.forEach(nodes::add);
        return nodes;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("Migration catalog contract field is blank: " + field);
        }
        return value.asText();
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException("Migration catalog contract contains a blank text value");
        }
        return node.asText();
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null) {
            return "";
        }
        if (!value.isTextual()) {
            throw new IllegalStateException("Migration catalog contract field is not text: " + field);
        }
        return value.asText();
    }

    private static String textAllowEmpty(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalStateException("Migration catalog contract field is not text: " + field);
        }
        return value.asText();
    }

    private static String definitionKind(String kind) {
        return switch (kind) {
            case "column", "constraint", "index", "trigger", "function" -> kind + "-definition";
            case "function-signature" -> kind;
            default -> throw new IllegalStateException("Migration catalog definition has an invalid kind");
        };
    }

    private static String booleanText(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalStateException("Migration catalog contract field is not boolean: " + field);
        }
        return Boolean.toString(value.asBoolean());
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash migration catalog contract", e);
        }
    }
}
