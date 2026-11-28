package com.scivicslab.chatui.suggestprompt;

/**
 * One fixed question of the Suggest prompt work frame ({@code suggest_question}).
 *
 * @param workType   one of the six work types, or {@link SuggestFrame#COMMON} for a question every type gets
 * @param questionId {@code common-1} to {@code common-4}, {@code investigate-1}, ... {@code write-2}, in table order
 * @param template   the English text; {@code {term}}, {@code {topic}} and {@code {step}} are filled in by code
 */
public record SuggestQuestion(String workType, String questionId, String template) {
}
