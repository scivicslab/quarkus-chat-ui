package com.scivicslab.chatui.suggestprompt;

import java.util.List;
import java.util.Optional;

/**
 * Where work frames are stored. Only the interface exists in this step; the H2 implementation comes later.
 */
public interface SuggestRepository {

    /** @return the work frame with the highest {@code epoch} of the squad, or empty when it has no row */
    Optional<SuggestFrame> loadCurrent(String squad);

    /** @return the highest {@code epoch} of the squad, or 0 when it has no row */
    int maxEpoch(String squad);

    /** Replaces the rows of one generation, in one transaction. */
    void save(SuggestFrame frame);

    /** @return all fixed questions */
    List<SuggestQuestion> questions();

    /** Replaces all fixed questions (at start-up). */
    void replaceQuestions(List<SuggestQuestion> questions);
}
