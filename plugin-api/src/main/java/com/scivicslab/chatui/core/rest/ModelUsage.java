package com.scivicslab.chatui.core.rest;

/**
 * The model a provider resolved a request to, and the limits it says that model has. Claude Code reports both in the
 * {@code modelUsage} object of its {@code result} line ({@code contextWindow} and {@code maxOutputTokens}, keyed by the
 * resolved model id), so an alias such as {@code sonnet} becomes {@code claude-sonnet-5-5} with its real window.
 *
 * @param model           the resolved model id, as the provider names it
 * @param contextWindow   the most input tokens one request can take; {@code 0} when the provider did not say
 * @param maxOutputTokens the most tokens one answer can have; {@code 0} when the provider did not say
 */
public record ModelUsage(String model, int contextWindow, int maxOutputTokens) {
}
