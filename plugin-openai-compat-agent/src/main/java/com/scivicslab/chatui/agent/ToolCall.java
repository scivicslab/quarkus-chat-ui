package com.scivicslab.chatui.agent;

/** One tool call the model wrote in its reply: a synthetic id, the tool name, and its arguments as JSON. */
public record ToolCall(String id, String name, String argumentsJson) {}
