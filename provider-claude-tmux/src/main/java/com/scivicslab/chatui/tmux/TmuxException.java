package com.scivicslab.chatui.tmux;

/**
 * Thrown when a {@code tmux} command fails, times out, or cannot be started.
 */
public class TmuxException extends RuntimeException {

    public TmuxException(String message) {
        super(message);
    }

    public TmuxException(String message, Throwable cause) {
        super(message, cause);
    }
}
