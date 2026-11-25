package com.scivicslab.chatui.harness;

import com.scivicslab.chatui.core.provider.LlmProvider.ModelEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What a Claude Code conversation's model value says: which model to ask the CLI for, and whether the conversation chose
 * the standard 200K context window instead of the 1M one. The window is part of the value the user picks, so each
 * conversation has its own and a restart or {@code /clear} keeps it with the model.
 *
 * <p>Measured with CLI 2.1.331 (the sonnet alias, one tiny call each): {@code sonnet}, {@code sonnet[1m]},
 * {@code claude-sonnet-5-5} and {@code claude-sonnet-5-5[1m]} all gave a 1,000,000 window, so no model value gives 200K;
 * only {@code CLAUDE_CODE_DISABLE_1M_CONTEXT=1} in the process's environment did (200,000). The mapping is therefore:</p>
 * <ul>
 *   <li>{@code sonnet} (a plain alias or id): {@code --model sonnet}, the variable not set; the CLI's own default, 1M.</li>
 *   <li>{@code sonnet[1m]}: {@code --model sonnet[1m]}, the variable not set; 1M, asked for explicitly.</li>
 *   <li>{@code sonnet[200k]}: {@code --model sonnet}, {@code CLAUDE_CODE_DISABLE_1M_CONTEXT=1}; 200K. The suffix is
 *       chat-ui's own and is never passed to the CLI.</li>
 * </ul>
 *
 * @param value             the model value as the conversation holds it
 * @param cliModel          what {@code --model} gets, or {@code null} for none
 * @param standardWindow    whether the process starts with 1M context disabled
 */
public record ClaudeModelChoice(String value, String cliModel, boolean standardWindow) {

    /** The CLI's switch that turns 1M context off (its binary names it and says a {@code [1m]} alias is then unavailable). */
    public static final String DISABLE_1M_ENV = "CLAUDE_CODE_DISABLE_1M_CONTEXT";

    /** chat-ui's suffix for the 200K window. */
    public static final String SUFFIX_200K = "[200k]";

    /** The model lines the CLI offers, as aliases: the CLI resolves each to the current model of its line. */
    static final List<String> FAMILIES = List.of("fable", "opus", "sonnet", "haiku");

    /** @return the choice a model value makes; a {@code null} or blank value asks for no {@code --model} */
    public static ClaudeModelChoice parse(String value) {
        if (value == null || value.isBlank()) return new ClaudeModelChoice(value, null, false);
        String v = value.strip();
        if (v.toLowerCase(Locale.ROOT).endsWith(SUFFIX_200K)) {
            String model = v.substring(0, v.length() - SUFFIX_200K.length()).strip();
            return new ClaudeModelChoice(v, model.isEmpty() ? null : model, true);
        }
        return new ClaudeModelChoice(v, v, false);
    }

    /**
     * @return what a running process was started with, as one string: a process started with another one must be started
     *         again for a changed model or window to reach it
     */
    public String launchKey() {
        return cliModel + "|" + standardWindow;
    }

    /** @return whether a process started on {@code launched} has to be started again to run on {@code current} */
    public static boolean needsRestart(String launched, String current) {
        return !Objects.equals(parse(launched).launchKey(), parse(current).launchKey());
    }

    /** @return the choices Settings offers: each family with its 1M window, then with the 200K window */
    static List<ModelEntry> entries(String type) {
        List<ModelEntry> out = new ArrayList<>();
        for (String family : FAMILIES) {
            out.add(new ModelEntry(family, type, null, family + " (1M)"));
            out.add(new ModelEntry(family + SUFFIX_200K, type, null, family + " (200K)"));
        }
        return out;
    }

    /** @return how a model value reads to the person: the model and its window, as chosen */
    public String describe() {
        if (cliModel == null) return "the CLI's default model";
        return cliModel + (standardWindow ? " (200K window, 1M context turned off)" : " (1M window, the CLI's default)");
    }
}
