package com.scivicslab.chatui.suggestprompt;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the fixed questions of the work frame from the resource {@code suggest-questions.yaml}, to put into the
 * repository at startup. The file maps each work type to a list of templates; a question's id is the work type and its
 * place in the list, counted from 1 ({@code investigate-2}).
 */
public final class SuggestQuestionsYaml {

    /** Where the questions are on the class path. */
    public static final String RESOURCE = "/suggest-questions.yaml";

    private SuggestQuestionsYaml() {
    }

    /** @return the questions in file order; an exception when the resource is missing or not of the expected shape */
    public static List<SuggestQuestion> load() throws IOException {
        try (InputStream in = SuggestQuestionsYaml.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("the resource " + RESOURCE + " is missing");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** @return the questions of one YAML text, in order */
    static List<SuggestQuestion> parse(String yaml) {
        Object root = new Yaml().load(yaml);
        if (!(root instanceof Map<?, ?> byType)) throw new IllegalArgumentException("the questions file is not a map");
        List<SuggestQuestion> out = new ArrayList<>();
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : byType.entrySet()) {
            String workType = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof List<?> templates)) {
                throw new IllegalArgumentException("work type " + workType + " does not hold a list");
            }
            for (Object t : templates) {
                if (t == null || String.valueOf(t).isBlank()) throw new IllegalArgumentException("a blank template in " + workType);
                int n = seen.merge(workType, 1, Integer::sum);
                out.add(new SuggestQuestion(workType, workType + "-" + n, String.valueOf(t)));
            }
        }
        return out;
    }
}
