package com.dbaagent.service.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads events-v1.schema.json and validates emitted events against it.
 * Unknown events fail validation entirely; known events with unknown property
 * keys still succeed but report which keys were dropped (so the emitter strips
 * them before transport).
 */
@Component
@Slf4j
public class EventSchemaRegistry {

    private final Map<String, Set<String>> allowedKeysByEvent;

    public EventSchemaRegistry() {
        this.allowedKeysByEvent = loadSchema();
    }

    private static Map<String, Set<String>> loadSchema() {
        try {
            JsonNode root = new ObjectMapper().readTree(
                    new ClassPathResource("telemetry/events-v1.schema.json").getInputStream());
            Map<String, Set<String>> result = new HashMap<>();
            JsonNode events = root.get("events");
            events.fieldNames().forEachRemaining(name -> {
                Set<String> keys = new HashSet<>();
                JsonNode props = events.get(name).get("properties");
                if (props != null) {
                    props.fieldNames().forEachRemaining(keys::add);
                }
                result.put(name, Set.copyOf(keys));
            });
            Map<String, Set<String>> loaded = Map.copyOf(result);
            log.info("EventSchemaRegistry: loaded {} event definitions", loaded.size());
            return loaded;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load events-v1.schema.json", e);
        }
    }

    public record ValidationResult(boolean valid, List<String> rejectedKeys) { }

    public ValidationResult validate(String event, Map<String, Object> properties) {
        Set<String> allowedKeys = allowedKeysByEvent.get(event);
        if (allowedKeys == null) {
            return new ValidationResult(false, List.of());
        }
        List<String> rejected = new ArrayList<>();
        for (String key : properties.keySet()) {
            if (!allowedKeys.contains(key)) rejected.add(key);
        }
        return new ValidationResult(true, rejected);
    }
}
