package com.scivicslab.chatui.tmux;

import com.scivicslab.chatui.core.rest.ChatEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the pure helpers of {@link TmuxLlmProvider}. The live
 * blocking-loop behaviour is covered by integration tests against real claude.
 */
@Tag("TmuxTuiDriver_Provider_260630_oo01")
@DisplayName("TmuxLlmProvider — event mapping and choice resolution")
class TmuxLlmProviderTest {

    @Test
    @DisplayName("identifies itself as the claude-tmux provider with interactive prompts")
    void identity_andCapabilities() {
        TmuxLlmProvider p = new TmuxLlmProvider("s", "claude", "sonnet");
        assertEquals("claude-tmux", p.id());
        assertTrue(p.capabilities().supportsInteractivePrompts());
        assertEquals("sonnet", p.getCurrentModel());
    }

    @Test
    @DisplayName("toPromptEvent carries the dialog question and option labels")
    void toPromptEvent_carriesQuestionAndOptions() {
        ApprovalRequested ar = new ApprovalRequested(
                "Allow Bash(rm)?", List.of("Yes", "No, and tell Claude what to do"));
        ChatEvent e = TmuxLlmProvider.toPromptEvent("pid-1", ar);

        assertEquals("prompt", e.type());
        assertEquals("pid-1", e.promptId());
        assertEquals("Allow Bash(rm)?", e.content());
        assertEquals(List.of("Yes", "No, and tell Claude what to do"), e.options());
    }

    @Test
    @DisplayName("mapResponseToChoice keeps a bare number as the option index")
    void mapResponseToChoice_bareNumber() {
        assertEquals("2", TmuxLlmProvider.mapResponseToChoice("2", null));
    }

    @Test
    @DisplayName("mapResponseToChoice maps affirmative and negative words to options 1 and 2")
    void mapResponseToChoice_words() {
        assertEquals("1", TmuxLlmProvider.mapResponseToChoice("yes", null));
        assertEquals("1", TmuxLlmProvider.mapResponseToChoice("approve", null));
        assertEquals("1", TmuxLlmProvider.mapResponseToChoice("trust", null));
        assertEquals("2", TmuxLlmProvider.mapResponseToChoice("no", null));
        assertEquals("2", TmuxLlmProvider.mapResponseToChoice("deny", null));
    }

    @Test
    @DisplayName("mapResponseToChoice defaults to option 1 for unrecognised input")
    void mapResponseToChoice_default() {
        assertEquals("1", TmuxLlmProvider.mapResponseToChoice("", null));
        assertEquals("1", TmuxLlmProvider.mapResponseToChoice("maybe", null));
    }
}
