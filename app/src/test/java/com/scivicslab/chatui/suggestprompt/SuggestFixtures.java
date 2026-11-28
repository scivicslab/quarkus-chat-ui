package com.scivicslab.chatui.suggestprompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The 16 fixed questions of design 340, in table order, with the question ids the design gives them. */
final class SuggestFixtures {

    private SuggestFixtures() {
    }

    static List<SuggestQuestion> questions() {
        String[][] table = {
            {"common", "What does \"{term}\" mean?"},
            {"common", "Your earlier explanation was not enough. Explain \"{topic}\" again, starting from the background."},
            {"common", "How far has \"{step}\" progressed?"},
            {"common", "What is still undecided before \"{step}\" can be finished?"},
            {"investigate", "What have you found so far about \"{topic}\", and how sure are you?"},
            {"investigate", "What is the evidence for this conclusion, and what else should we check?"},
            {"design", "What alternatives did you consider for \"{topic}\", and why this one?"},
            {"design", "What are the risks of this design, and what does it leave undecided?"},
            {"implement", "Which files will \"{step}\" change, and what is the smallest change that works?"},
            {"implement", "How will you keep the existing behavior unchanged while doing \"{step}\"?"},
            {"verify", "How was \"{step}\" checked, and what raw output proves it?"},
            {"verify", "What has not been checked yet?"},
            {"operate", "What does \"{step}\" change on the running system, and can it be undone?"},
            {"operate", "What do you need from me before \"{step}\"?"},
            {"write", "Who is the reader of this document, and what should they be able to do afterwards?"},
            {"write", "Read it again and list the sentences that are unclear."},
        };
        List<SuggestQuestion> out = new ArrayList<>();
        Map<String, Integer> count = new HashMap<>();
        for (String[] row : table) {
            int n = count.merge(row[0], 1, Integer::sum);
            out.add(new SuggestQuestion(row[0], row[0] + "-" + n, row[1]));
        }
        return out;
    }
}
