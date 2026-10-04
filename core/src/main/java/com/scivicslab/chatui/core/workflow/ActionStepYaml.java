package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The step a workflow YAML writes to call one action, as the Actions tab shows it.
 *
 * <p>When the action's Javadoc holds a {@code <pre>} block, that block is the step: it is what the
 * author wrote, with the real keys and jexl expressions. Otherwise the step is composed from what the
 * catalog declares — the {@code actor:} and {@code method:} lines, and an {@code arguments:} map with
 * one entry per JSON Schema property, the type in angle brackets as the placeholder, required ones
 * first — or, when nothing is declared, the two lines alone.</p>
 */
public final class ActionStepYaml {

    private ActionStepYaml() {
    }

    /**
     * @param actor       the actor name the YAML uses ({@code harness}, {@code queue}, ...)
     * @param action      the action name
     * @param description what {@code ActionCatalog.describe} returned for the action
     * @return the YAML step text, without a trailing newline
     */
    public static String of(String actor, String action, JsonNode description) {
        JsonNode example = description == null ? null : description.get("example");
        if (example != null && example.isTextual() && !example.asText().isBlank()) {
            return example.asText().strip();
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("- actor: ").append(actor).append('\n');
        yaml.append("  method: ").append(action);
        JsonNode schema = description == null ? null : description.get("schema");
        JsonNode properties = schema == null ? null : schema.get("properties");
        if (properties == null || !properties.isObject() || properties.isEmpty()) {
            return yaml.toString();
        }
        List<String> required = new ArrayList<>();
        JsonNode requiredNode = schema.get("required");
        if (requiredNode != null && requiredNode.isArray()) {
            for (JsonNode name : requiredNode) {
                required.add(name.asText());
            }
        }
        List<String> entries = new ArrayList<>();
        for (String name : required) {
            JsonNode prop = properties.get(name);
            if (prop != null) {
                entries.add(name + ": " + placeholder(prop));
            }
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = properties.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            if (!required.contains(field.getKey())) {
                entries.add(field.getKey() + ": " + placeholder(field.getValue()));
            }
        }
        yaml.append("\n  arguments: {").append(String.join(", ", entries)).append('}');
        return yaml.toString();
    }

    /** The JSON Schema type of the property in angle brackets, quoted when the YAML would need a string. */
    private static String placeholder(JsonNode property) {
        String type = property.hasNonNull("type") ? property.get("type").asText() : "any";
        if (property.hasNonNull("type") && property.get("type").isArray() && !property.get("type").isEmpty()) {
            type = property.get("type").get(0).asText();
        }
        return "string".equals(type) ? "\"<string>\"" : "<" + type + ">";
    }
}
