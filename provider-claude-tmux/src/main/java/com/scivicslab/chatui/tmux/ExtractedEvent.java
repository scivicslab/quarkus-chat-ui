package com.scivicslab.chatui.tmux;

/**
 * A typed event extracted from a tmux {@code capture-pane} snapshot of the
 * Claude Code interactive TUI.
 *
 * <p>The {@link ScreenExtractor} converts the rendered, redrawing terminal
 * screen into a stream of these discrete events so that the Web UI receives
 * structured content instead of a raw terminal image.
 */
public sealed interface ExtractedEvent
        permits AssistantMessage, ToolResult, ApprovalRequested {
}
