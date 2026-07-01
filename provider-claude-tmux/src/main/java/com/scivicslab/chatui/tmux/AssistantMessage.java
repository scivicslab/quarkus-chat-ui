package com.scivicslab.chatui.tmux;

/**
 * A line (or contiguous block) of Claude Code assistant output, identified on
 * the TUI by the {@code ●} bullet marker.
 *
 * @param text the message body with the bullet marker and surrounding chrome removed
 */
public record AssistantMessage(String text) implements ExtractedEvent {
}
