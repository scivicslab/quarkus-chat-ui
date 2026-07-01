package com.scivicslab.chatui.tmux;

/**
 * The result of a tool invocation, identified on the TUI by the {@code ⎿} marker
 * (for example the output of a Bash command, or {@code /compact}'s reply).
 *
 * @param text the result body with the marker and surrounding chrome removed
 */
public record ToolResult(String text) implements ExtractedEvent {
}
