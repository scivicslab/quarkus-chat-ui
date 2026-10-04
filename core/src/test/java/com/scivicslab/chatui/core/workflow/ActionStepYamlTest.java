package com.scivicslab.chatui.core.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure JUnit 5: the step composed for an action whose Javadoc has no example of its own. */
class ActionStepYamlTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode description(String json) throws Exception {
        return (ObjectNode) JSON.readTree(json);
    }

    @Test
    @DisplayName("a record argument: one entry per property, required first, the type as the placeholder")
    void fromSchema() throws Exception {
        ObjectNode d = description("{\"schema\":{\"type\":\"object\","
                + "\"properties\":{\"limit\":{\"type\":\"integer\"},\"text\":{\"type\":\"string\"}},"
                + "\"required\":[\"text\"]}}");
        assertEquals("- actor: queue\n  method: enqueue\n  arguments: {text: \"<string>\", limit: <integer>}",
                ActionStepYaml.of("queue", "enqueue", d));
    }

    @Test
    @DisplayName("a raw-String action with no example: the two lines, nothing invented about the String")
    void rawString() throws Exception {
        ObjectNode d = description("{\"schema\":null,\"note\":\"This action takes a raw String; its shape is not declared.\","
                + "\"argument\":{\"name\":\"args\",\"description\":\"ignored\"}}");
        assertEquals("- actor: harness\n  method: refine", ActionStepYaml.of("harness", "refine", d));
    }

    @Test
    @DisplayName("an example in the Javadoc wins over the composed step")
    void javadocExample() throws Exception {
        ObjectNode d = description("{\"example\":\"- actor: out\\n  method: print\\n  arguments: {message: hi}\","
                + "\"schema\":{\"properties\":{\"message\":{\"type\":\"string\"}}}}");
        assertEquals("- actor: out\n  method: print\n  arguments: {message: hi}", ActionStepYaml.of("out", "print", d));
    }
}
