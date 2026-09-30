package com.scivicslab.chatui.core.workflow;

/**
 * One prompt-queue item as a workflow sees it.
 *
 * @param kind  {@code "prompt"} or {@code "workflow"}
 * @param text  the prompt text, or the workflow's display title
 * @param yaml  the workflow YAML; null for a prompt
 * @param input the workflow's input JSON; null for a prompt
 * @param auto  whether the queue runs it without a click
 */
public record QueueItem(String kind, String text, String yaml, String input, boolean auto) {

    public static QueueItem prompt(String text) {
        return new QueueItem("prompt", text, null, null, true);
    }

    public static QueueItem workflow(String title, String yaml, String input) {
        return new QueueItem("workflow", title, yaml, input, true);
    }
}
