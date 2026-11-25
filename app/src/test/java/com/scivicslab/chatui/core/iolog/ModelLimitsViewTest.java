package com.scivicslab.chatui.core.iolog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

/** A conversation's resolved model and limits are read from its {@code turnN/model-limits} entries. */
class ModelLimitsViewTest {

    private static LogEntry entry(long id, String label, String message) {
        return new LogEntry(id, 1L, LocalDateTime.of(2026, 11, 25, 3, 0, 0).plusSeconds(id),
                "agent", label, "action", LogLevel.INFO, message, null, null);
    }

    @Test
    void theMessageIsWhatTheReaderParses() {
        IoLogView.ModelLimits written = new IoLogView.ModelLimits(3, "claude-sonnet-5-5", 1_000_000, 128_000, "high");
        assertEquals(written, IoLogView.modelLimitsOf(List.of(entry(1, "turn3/model-limits", written.toMessage()))));
    }

    @Test
    void theLatestTurnsEntryWins_andAnEmptyEffortStaysEmpty() {
        IoLogView.ModelLimits second = new IoLogView.ModelLimits(2, "claude-opus-5-5", 200_000, 64_000, "");
        IoLogView.ModelLimits got = IoLogView.modelLimitsOf(List.of(
                entry(1, "turn1/model-limits", new IoLogView.ModelLimits(1, "claude-sonnet-5-5", 1_000_000, 128_000, "high").toMessage()),
                entry(2, "turn2/model-limits", second.toMessage())));
        assertEquals(second, got);
        assertEquals("", got.effort());
    }

    @Test
    void aSessionRecordedBeforeTheEntryExistedHasNone_andOtherLabelsAreLeftAlone() {
        assertNull(IoLogView.modelLimitsOf(List.of(
                entry(1, "turn1/step1/llm", "REQUEST:\n{}\n\nRESPONSE:\nok\n\nUSAGE: promptTokens=5 completionTokens=1"),
                entry(2, "turn1/answer-meta", "MODEL: sonnet\nDURATION_MS: 5"),
                entry(3, "turn1/ids", "TURN_ID: t1"))));
    }

    @Test
    void anEntryWithoutAModelIsNotOne_andABadNumberIsNoLimit() {
        assertNull(IoLogView.modelLimitsOf(List.of(entry(1, "turn1/model-limits", "CONTEXT_WINDOW: 5"))));
        IoLogView.ModelLimits got = IoLogView.modelLimitsOf(List.of(
                entry(1, "turn1/model-limits", "MODEL: m\nCONTEXT_WINDOW: lots\nMAX_OUTPUT_TOKENS: -4\nEFFORT: low")));
        assertEquals(new IoLogView.ModelLimits(1, "m", 0, 0, "low"), got);
    }

    @Test
    void theTurnCounterStillSeesTheEntry() {
        assertEquals(4, IoLogView.lastTurnNumberOf(List.of(entry(1, "turn4/model-limits", "MODEL: m"))));
    }
}
