package com.scivicslab.chatui.audittrail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** What the step-limit and time-limit settings accept: whole numbers, as numbers or as text. */
class ChatResourceLimitsTest {

    @Test
    void aWholeNumberIsTakenAsNumberOrText() {
        assertEquals(100, ChatResource.wholeNumber(100));
        assertEquals(100, ChatResource.wholeNumber(100.0));
        assertEquals(10, ChatResource.wholeNumber(" 10 "));
        assertEquals(0, ChatResource.wholeNumber(0));
    }

    @Test
    void aFractionTextOrNothingIsNotAWholeNumber() {
        assertNull(ChatResource.wholeNumber(1.5));
        assertNull(ChatResource.wholeNumber("many"));
        assertNull(ChatResource.wholeNumber(null));
        assertNull(ChatResource.wholeNumber(true));
        assertNull(ChatResource.wholeNumber(1e12));
    }

    @Test
    void theBoundsAreOneToTenThousandStepsAndZeroToADayOfMinutes() {
        assertEquals(10_000, ChatResource.MAX_STEP_LIMIT);
        assertEquals(1_440, ChatResource.MAX_TIME_LIMIT_MINUTES);
    }
}
