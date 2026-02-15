package com.luketn.mongodb.iceberg.sync.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads daemon configuration from YAML, applies environment variable substitution,
 * applies defaults, and validates required fields.
 */
public final class ConfigLoader {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final ObjectMapper objectMapper;

    public ConfigLoader() {
        this.objectMapper = new ObjectMapper(new YAMLFactory());
    }

    public SyncConfig load(Path configPath) {
        if (configPath == null) {
            throw new SyncConfigException("config path is required");
        }
        if (!Files.exists(configPath)) {
            throw new SyncConfigException("config file does not exist: " + configPath);
        }

        try {
            JsonNode root = objectMapper.readTree(configPath.toFile());
            JsonNode substituted = substituteEnvVars(root);
            SyncConfig parsed = objectMapper.treeToValue(substituted, SyncConfig.class);
            if (parsed == null) {
                throw new SyncConfigException("config file is empty: " + configPath);
            }
            return parsed.normalizeAndValidate();
        } catch (JsonProcessingException e) {
            throw new SyncConfigException("invalid YAML in config file: " + configPath + ": " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new SyncConfigException("failed to read config file: " + configPath, e);
        }
    }

    private JsonNode substituteEnvVars(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }

        if (node.isTextual()) {
            String substituted = substituteText(node.textValue());
            return objectMapper.getNodeFactory().textNode(substituted);
        }

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                        .set(field.getKey(), substituteEnvVars(field.getValue()));
            }
            return node;
        }

        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) node)
                        .set(i, substituteEnvVars(node.get(i)));
            }
            return node;
        }

        return node;
    }

    private String substituteText(String value) {
        Matcher matcher = ENV_PATTERN.matcher(value);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = System.getenv(varName);
            if (replacement == null) {
                throw new SyncConfigException("environment variable is not set: " + varName);
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
