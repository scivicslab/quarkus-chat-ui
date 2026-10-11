package com.scivicslab.chatui.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Translating one message into a chosen language (QuarkusChatUiMessageTranslation_261011_oo01). */
@DisplayName("会話の 1 件を訳す")
class MessageTranslationTest {

    @Test
    @DisplayName("言語は英語と English Toolkit の 12 で、同じ順・同じコード")
    void languagesAreEnglishThenTheEnglishToolkitTwelve() {
        List<String> codes = TranslationLanguages.ALL.stream().map(TranslationLanguages.Language::code).toList();
        assertEquals(List.of("en", "ja", "zh-Hans", "zh-Hant", "ko", "hi", "es", "ar", "fr", "pt", "ru", "id", "de"), codes);
    }

    @Test
    @DisplayName("知らないコードは見つからない")
    void unknownCodeIsNotFound() {
        assertTrue(TranslationLanguages.find("xx").isEmpty());
        assertTrue(TranslationLanguages.find(null).isEmpty());
        assertEquals("ja", TranslationLanguages.find(" JA ").orElseThrow().code());
    }

    @Test
    @DisplayName("指示文に言語名が入り、訳す文がそのまま最後に付く")
    void promptNamesTheLanguageAndEndsWithTheText() {
        String text = "## 見出し\n`a.b()` を呼ぶ。";
        String prompt = TranslationPrompt.of(text, "English");
        assertTrue(prompt.startsWith("Translate the following text into natural English."), prompt);
        assertTrue(prompt.contains("Reply with ONLY the English translation"), prompt);
        assertTrue(prompt.endsWith("\n\n" + text), prompt);
    }

    @Test
    @DisplayName("問い合わせの本文にモデル・指示文・思考しない指定が入る")
    void requestBodyCarriesModelPromptAndNoThinking() {
        JSONObject body = new JSONObject(MessageTranslator.requestBody("google/gemma-4-26B-A4B-it", "PROMPT"));
        assertEquals("google/gemma-4-26B-A4B-it", body.getString("model"));
        assertEquals("PROMPT", body.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertFalse(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"));
        assertFalse(body.getBoolean("stream"));
    }
}
