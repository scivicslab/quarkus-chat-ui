package com.scivicslab.chatui.translate;

import org.json.JSONArray;
import org.json.JSONObject;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Translating one message, for the Translate list under each message
 * ({@code QuarkusChatUiMessageTranslation_261011_oo01}).
 *
 * <p>{@code GET /api/translate/languages} → {@code [{code, name}, ...]}, English first.<br>
 * {@code POST /api/translate} {@code {"text", "language"}} → {@code {"translation", "language",
 * "model"}}; 400 for no text or an unknown language, 502 when the model gave no translation.</p>
 */
@Path("/api/translate")
public class MessageTranslationResource {

    @Inject
    MessageTranslator translator;

    @GET
    @Path("/languages")
    @Produces(MediaType.APPLICATION_JSON)
    public String languages() {
        JSONArray list = new JSONArray();
        for (TranslationLanguages.Language l : TranslationLanguages.ALL) {
            list.put(new JSONObject().put("code", l.code()).put("name", l.name()));
        }
        return list.toString();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response translate(String body) {
        JSONObject in;
        try {
            in = new JSONObject(body == null ? "" : body);
        } catch (Exception e) {
            return error(400, "the body must be a JSON object");
        }
        String text = in.optString("text", "");
        String language = in.optString("language", "");
        if (text.isBlank()) return error(400, "text is required");
        if (TranslationLanguages.find(language).isEmpty()) return error(400, "unknown language: " + language);
        MessageTranslator.Result r = translator.translate(text, language);
        if (r.translation() == null) return error(502, r.error());
        return Response.ok(new JSONObject().put("translation", r.translation()).put("language", language)
                .put("model", r.model()).toString()).build();
    }

    private static Response error(int status, String message) {
        return Response.status(status).entity(new JSONObject().put("type", "error").put("message", message).toString())
                .type(MediaType.APPLICATION_JSON).build();
    }
}
