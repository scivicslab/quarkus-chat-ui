package com.scivicslab.chatui.agent;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Notices a model that makes the same tool call over and over. One instance keeps one turn's run of identical calls:
 * the same tool name, the same arguments (compared as JSON, so key order and spacing do not matter) and the same
 * result text. A call that differs in any of the three, or any text the model writes, starts the count again.
 */
public final class RepeatedCallDetector {

    /** How many identical calls in a row make a repetition. */
    public static final int REPEAT_LIMIT = 3;

    private String tool;
    private String arguments;
    private String result;
    private int count;

    /**
     * Records one executed call.
     *
     * @param tool      the tool's name
     * @param arguments the call's arguments, as the JSON text the model wrote
     * @param result    what the call returned
     */
    public void record(String tool, String arguments, String result) {
        String args = arguments == null ? "" : arguments;
        String res = result == null ? "" : result;
        if (count > 0 && tool != null && tool.equals(this.tool) && sameArguments(this.arguments, args)
                && res.equals(this.result)) {
            count++;
            return;
        }
        this.tool = tool;
        this.arguments = args;
        this.result = res;
        this.count = 1;
    }

    /** The model wrote text, or the turn begins: whatever run there was is over. */
    public void reset() {
        tool = null;
        arguments = null;
        result = null;
        count = 0;
    }

    /** @return how many identical calls the last ones were */
    public int count() {
        return count;
    }

    /** @return whether the last {@link #REPEAT_LIMIT} calls were identical */
    public boolean repeated() {
        return count >= REPEAT_LIMIT;
    }

    /** @return the tool of the repeated call, or {@code null} when none has been recorded */
    public String tool() {
        return tool;
    }

    /** @return the repeated call's arguments as the model wrote them, or {@code null} */
    public String arguments() {
        return arguments;
    }

    /** @return the repeated call's result, or {@code null} */
    public String result() {
        return result;
    }

    /**
     * @return whether two argument texts are the same call's: equal text, or the same JSON whatever the key order and
     *         spacing
     */
    public static boolean sameArguments(String a, String b) {
        if (a == null || b == null) return a == b;
        if (a.equals(b)) return true;
        try {
            return new JSONObject(a).similar(new JSONObject(b));
        } catch (JSONException e) {
            return false;
        }
    }
}
