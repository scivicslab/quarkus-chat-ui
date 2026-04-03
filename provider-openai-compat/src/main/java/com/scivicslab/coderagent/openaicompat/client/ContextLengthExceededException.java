package com.scivicslab.coderagent.openaicompat.client;

/**
 * Thrown when the prompt exceeds the model's maximum context length.
 */
public class ContextLengthExceededException extends RuntimeException {
    public ContextLengthExceededException(String message) { super(message); }
}
