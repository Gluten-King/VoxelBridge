package com.voxelbridge.verification;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Produces a deterministic, review-oriented explanation of a semantic snapshot change. */
final class SemanticSnapshotDiff {
    private static final int DIFF_SCHEMA_VERSION = 1;
    private static final Set<String> NAMED_COLLECTIONS = Set.of("assertions", "materials", "images");

    private SemanticSnapshotDiff() {}

    static Result write(Path expectedPath, Path actualPath, Path outputPath) throws IOException {
        JsonNode expected = GoldenJson.mapper().readTree(expectedPath.toFile());
        JsonNode actual = GoldenJson.mapper().readTree(actualPath.toFile());

        List<Map<String, Object>> topLevelChanges = changedFields(expected, actual, NAMED_COLLECTIONS);
        CollectionDiff assertions = namedCollection(expected, actual, "assertions", "id");
        CollectionDiff materials = namedCollection(expected, actual, "materials", "name");
        CollectionDiff images = namedCollection(expected, actual, "images", "id");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("topLevelChanges", topLevelChanges.size());
        putCounts(summary, "assertions", assertions);
        putCounts(summary, "materials", materials);
        putCounts(summary, "images", images);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", DIFF_SCHEMA_VERSION);
        document.put("expected", expectedPath.toAbsolutePath().normalize().toString());
        document.put("actual", actualPath.toAbsolutePath().normalize().toString());
        document.put("summary", summary);
        document.put("topLevelChanges", topLevelChanges);
        document.put("assertions", assertions.asMap());
        document.put("materials", materials.asMap());
        document.put("images", images.asMap());
        GoldenJson.writeValue(outputPath, document);

        String detail = topLevelChanges.size() + " top-level, "
                + describe("material", materials) + ", "
                + describe("image", images) + ", "
                + describe("assertion", assertions);
        return new Result(outputPath.toAbsolutePath().normalize(), detail);
    }

    private static void putCounts(Map<String, Object> summary, String prefix, CollectionDiff diff) {
        summary.put(prefix + "Added", diff.added().size());
        summary.put(prefix + "Removed", diff.removed().size());
        summary.put(prefix + "Changed", diff.changed().size());
    }

    private static String describe(String label, CollectionDiff diff) {
        return diff.added().size() + " " + label + " added/"
                + diff.removed().size() + " removed/"
                + diff.changed().size() + " changed";
    }

    private static CollectionDiff namedCollection(
            JsonNode expectedRoot, JsonNode actualRoot, String field, String keyField) {
        Map<String, JsonNode> expected = index(expectedRoot.path(field), keyField);
        Map<String, JsonNode> actual = index(actualRoot.path(field), keyField);
        List<Map<String, Object>> added = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        List<Map<String, Object>> changed = new ArrayList<>();

        TreeSet<String> keys = new TreeSet<>();
        keys.addAll(expected.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            JsonNode before = expected.get(key);
            JsonNode after = actual.get(key);
            if (before == null) {
                added.add(collectionEntry(key, after));
            } else if (after == null) {
                removed.add(collectionEntry(key, before));
            } else if (!before.equals(after)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", key);
                entry.put("changes", changedFields(before, after, Set.of()));
                changed.add(entry);
            }
        }
        return new CollectionDiff(List.copyOf(added), List.copyOf(removed), List.copyOf(changed));
    }

    private static Map<String, JsonNode> index(JsonNode array, String keyField) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        if (!array.isArray()) {
            return result;
        }
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (JsonNode value : array) {
            String base = value.path(keyField).asText("<missing>");
            int occurrence = occurrences.merge(base, 1, Integer::sum);
            String key = occurrence == 1 ? base : base + "[" + occurrence + "]";
            result.put(key, value);
        }
        return result;
    }

    private static Map<String, Object> collectionEntry(String key, JsonNode value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }

    private static List<Map<String, Object>> changedFields(
            JsonNode expected, JsonNode actual, Set<String> excludedFields) {
        TreeSet<String> fields = new TreeSet<>();
        addFieldNames(fields, expected);
        addFieldNames(fields, actual);
        fields.removeAll(excludedFields);

        List<Map<String, Object>> changes = new ArrayList<>();
        for (String field : fields) {
            JsonNode before = expected.get(field);
            JsonNode after = actual.get(field);
            if (nodesEqual(before, after)) {
                continue;
            }
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("field", field);
            change.put("expected", before);
            change.put("actual", after);
            changes.add(change);
        }
        return List.copyOf(changes);
    }

    private static void addFieldNames(Set<String> target, JsonNode node) {
        if (node == null || !node.isObject()) {
            return;
        }
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            target.add(names.next());
        }
    }

    private static boolean nodesEqual(JsonNode first, JsonNode second) {
        return first == null ? second == null : first.equals(second);
    }

    record Result(Path path, String detail) {}

    private record CollectionDiff(
            List<Map<String, Object>> added,
            List<Map<String, Object>> removed,
            List<Map<String, Object>> changed) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("added", added);
            result.put("removed", removed);
            result.put("changed", changed);
            return result;
        }
    }
}
