package com.scivicslab.chatui.core.iolog;

import com.scivicslab.turingworkflow.plugins.logdb.LogEntry;
import com.scivicslab.turingworkflow.plugins.logdb.LogLevel;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Suggest prompt writes its rows under {@code prompt-suggestion}. Rows stored before the rename carry
 * {@code prompt-prediction}; they are not rewritten, and a read of {@code prompt-suggestion} gives both.
 */
class PromptSuggestionLabelTest {

    private static long nextId = 1;

    private static LogEntry row(String label, String message) {
        return new LogEntry(nextId++, 1L, LocalDateTime.now(), "agent", label, null,
                LogLevel.INFO, message, null, null);
    }

    @Test
    void aRowStoredAsPromptPredictionIsReadAsPromptSuggestion() {
        List<LogEntry> raw = List.of(
                row("prompt-prediction", "old reply"),
                row("translation", "other"),
                row("prompt-suggestion", "new reply"));
        assertEquals("new reply", IoLogView.latestOf(raw, IoLogView.PROMPT_SUGGESTION_LABEL));
        assertEquals("old reply", IoLogView.latestOf(raw.subList(0, 2), IoLogView.PROMPT_SUGGESTION_LABEL),
                "with no new row, the old row is what the read finds");
    }

    @Test
    void theOldLabelIsNotAnAliasTheOtherWayAndOtherLabelsAreUntouched() {
        assertTrue(IoLogView.sameLabel("prompt-suggestion", "prompt-prediction"));
        assertTrue(IoLogView.sameLabel("prompt-suggestion", "prompt-suggestion"));
        assertFalse(IoLogView.sameLabel("prompt-prediction", "prompt-suggestion"));
        assertFalse(IoLogView.sameLabel("translation", "prompt-prediction"));
        assertFalse(IoLogView.sameLabel("prompt-suggestion", null));
    }
}
