package com.marketingpanel.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Static access to the application ObjectMapper for places that are not Spring beans. */
@Component
public class Json {
    private static ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public Json(ObjectMapper m) {
        mapper = m;
    }

    public static ObjectMapper mapper() {
        return mapper;
    }

    public static String write(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static JsonNode tree(String s) {
        try {
            return mapper.readTree(s);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static JsonNode tree(Object o) {
        return mapper.valueToTree(o);
    }

    public static <T> T read(String s, Class<T> c) {
        try {
            return mapper.readValue(s, c);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static Map<String, Object> map(String s) {
        if (s == null || s.isBlank()) return Map.of();
        try {
            return mapper.readValue(s, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static List<Object> list(String s) {
        if (s == null || s.isBlank()) return List.of();
        try {
            return mapper.readValue(s, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
