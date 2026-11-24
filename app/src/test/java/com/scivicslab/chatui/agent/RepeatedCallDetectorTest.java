package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** When a run of tool calls counts as the model repeating itself. */
class RepeatedCallDetectorTest {

    private static final String BASH = "{\"command\":\"echo done\"}";

    private static RepeatedCallDetector afterCalls(String... toolArgsResult) {
        RepeatedCallDetector d = new RepeatedCallDetector();
        for (int i = 0; i < toolArgsResult.length; i += 3) {
            d.record(toolArgsResult[i], toolArgsResult[i + 1], toolArgsResult[i + 2]);
        }
        return d;
    }

    @Test
    void theNumberIsThree() {
        assertEquals(3, RepeatedCallDetector.REPEAT_LIMIT);
    }

    @Test
    void threeIdenticalCallsInARowAreARepetition() {
        RepeatedCallDetector d = afterCalls("bash", BASH, "exit 0\ndone", "bash", BASH, "exit 0\ndone",
                "bash", BASH, "exit 0\ndone");
        assertTrue(d.repeated());
        assertEquals(3, d.count());
        assertEquals("bash", d.tool());
    }

    @Test
    void twoIdenticalCallsAreNot() {
        assertFalse(afterCalls("bash", BASH, "r", "bash", BASH, "r").repeated());
    }

    @Test
    void twoIdenticalCallsThenADifferentOneAreNot() {
        RepeatedCallDetector d = afterCalls("bash", BASH, "r", "bash", BASH, "r", "read", "{\"path\":\"a\"}", "r");
        assertFalse(d.repeated());
        assertEquals(1, d.count());
    }

    @Test
    void theSameToolWithADifferentArgumentIsNot() {
        assertFalse(afterCalls("bash", "{\"command\":\"echo 1\"}", "r", "bash", "{\"command\":\"echo 2\"}", "r",
                "bash", "{\"command\":\"echo 3\"}", "r").repeated());
    }

    @Test
    void theSameCallWithADifferentResultIsNot() {
        assertFalse(afterCalls("bash", BASH, "r1", "bash", BASH, "r2", "bash", BASH, "r3").repeated());
        assertFalse(afterCalls("bash", BASH, "r", "bash", BASH, "r", "bash", BASH, "r changed").repeated());
    }

    @Test
    void anotherToolWithTheSameArgumentsAndResultIsNot() {
        assertFalse(afterCalls("bash", BASH, "r", "run", BASH, "r", "bash", BASH, "r").repeated());
    }

    @Test
    void keyOrderAndSpacingOfTheArgumentsDoNotMatter() {
        assertTrue(afterCalls("grep", "{\"pattern\":\"x\",\"path\":\"src\"}", "r",
                "grep", "{ \"path\" : \"src\", \"pattern\" : \"x\" }", "r",
                "grep", "{\"path\":\"src\",\n \"pattern\":\"x\"}", "r").repeated());
    }

    @Test
    void textBetweenCallsStartsTheCountAgain() {
        RepeatedCallDetector d = afterCalls("bash", BASH, "r", "bash", BASH, "r");
        d.reset();
        d.record("bash", BASH, "r");
        assertFalse(d.repeated());
        d.record("bash", BASH, "r");
        d.record("bash", BASH, "r");
        assertTrue(d.repeated());
    }

    @Test
    void argumentsThatAreNotJsonAreComparedAsText() {
        assertTrue(afterCalls("t", "not json", "r", "t", "not json", "r", "t", "not json", "r").repeated());
        assertFalse(afterCalls("t", "not json", "r", "t", "other text", "r", "t", "not json", "r").repeated());
    }

    @Test
    void aMoreThanThreeRunKeepsCounting() {
        RepeatedCallDetector d = new RepeatedCallDetector();
        for (int i = 0; i < 5; i++) d.record("bash", BASH, "r");
        assertEquals(5, d.count());
        assertTrue(d.repeated());
    }
}
