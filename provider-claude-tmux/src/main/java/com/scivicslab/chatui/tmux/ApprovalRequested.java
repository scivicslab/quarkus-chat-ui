package com.scivicslab.chatui.tmux;

import java.util.List;

/**
 * A permission / confirmation dialog detected on the TUI: a numbered choice box
 * (for example the workspace-trust dialog, or a tool-execution permission
 * prompt) that ends with an {@code Enter to confirm} line.
 *
 * <p>The session must surface this to the user and answer it by sending the
 * chosen option number followed by Enter through {@code tmux send-keys}.
 *
 * @param prompt  the question text shown above the options
 * @param options the option labels in display order (option 1 first)
 */
public record ApprovalRequested(String prompt, List<String> options) implements ExtractedEvent {
}
