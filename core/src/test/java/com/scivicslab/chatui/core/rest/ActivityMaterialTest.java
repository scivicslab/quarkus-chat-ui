package com.scivicslab.chatui.core.rest;

import com.scivicslab.chatui.core.actor.ChatActor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for the material {@link ActivityResource} hands the model.
 *
 * <p>Exercises the load-bearing path: what a model is given decides what comes back, and what is
 * given must be short enough that twenty-four entries of a long conversation still fit in one
 * request ({@code ActivitySummary_260905_oo01}).</p>
 */
class ActivityMaterialTest {

    @Test
    void material_labelsWhoSaidWhat() {
        String out = ActivityResource.material(List.of(
                new ChatActor.HistoryEntry("user", "検索フォームのセキュリティを見て"),
                new ChatActor.HistoryEntry("assistant", "XSSとパストラバーサルを確認した")));

        assertEquals("""
                Q: 検索フォームのセキュリティを見て
                A: XSSとパストラバーサルを確認した
                """, out);
    }

    @Test
    void clip_collapsesWhitespaceSoOneEntryStaysOneLine() {
        assertEquals("a b c", ActivityResource.clip("a\n  b\tc"));
    }

    @Test
    void clip_cutsALongAnswerToItsOpening() {
        String long_ = "x".repeat(1000);

        String clipped = ActivityResource.clip(long_);

        assertTrue(clipped.length() < long_.length(), "a 1000-character answer must not pass through whole");
        assertTrue(clipped.endsWith("…"), "the cut must be visible: " + clipped.substring(clipped.length() - 5));
    }

    @Test
    void clip_ofNothing_isEmpty() {
        assertEquals("", ActivityResource.clip(null));
    }
}
