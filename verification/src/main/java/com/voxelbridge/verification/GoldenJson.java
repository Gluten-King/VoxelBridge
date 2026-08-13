package com.voxelbridge.verification;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class GoldenJson {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .build();

    private GoldenJson() {}

    public static void write(Path path, GoldenSnapshot snapshot) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(path.toFile(), snapshot);
    }

    public static GoldenSnapshot read(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), GoldenSnapshot.class);
    }

    public static String pretty(GoldenSnapshot snapshot) throws IOException {
        return MAPPER.writeValueAsString(snapshot);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static void writeValue(Path path, Object value) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MAPPER.writeValue(path.toFile(), value);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readMap(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), Map.class);
    }
}
