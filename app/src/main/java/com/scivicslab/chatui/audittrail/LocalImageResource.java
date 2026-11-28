package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;

/**
 * Serves an image file that lives on this machine's file system, so that an answer which writes
 * {@code ![...](/home/devteam/works/shot.png)} shows the picture instead of a broken image icon
 * ({@code LocalImageInAnswer_260904_oo01}).
 *
 * <p>Markdown puts that path straight into {@code <img src>}, and the browser resolves it against
 * the chat page's own origin — it becomes a request to this server for {@code /home/devteam/...},
 * which no static resource answers. The browser cannot read the file itself; only the server can.
 * So the page rewrites such a source to this endpoint, and this endpoint reads the file.</p>
 *
 * <p>The readable range is exactly {@link FileAccessScope}'s read roots — the same range the
 * {@code read} tool may return to a conversation ({@code FileAccessScope_260830_oo01}). Reading
 * over HTTP is not a smaller act than reading through a tool: anyone who can open the chat page
 * gets whatever this returns, so widening it here and not there would put the wider range in the
 * place with no audit record.</p>
 */
@Path("/api/local-image")
public class LocalImageResource {

    @Inject
    ChatUiActorSystem actorSystem;

    /**
     * The image types served, by file-name extension. A fixed list rather than a probe of the
     * bytes: the point is to serve pictures a conversation produced, and an extension the list does
     * not name is refused rather than guessed at.
     */
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "svg", "image/svg+xml");

    /** The largest file served. A picture a conversation draws is far smaller; a bigger file is refused. */
    static final long MAX_BYTES = 20L * 1024 * 1024;

    /**
     * Returns the image at {@code path}.
     *
     * @param path an absolute path on this machine, or a path relative to the write directory
     * @return the image bytes, or 400 when the path is missing, starts with {@code ~}, or is not an
     *         image type, 403 when it lies outside the readable range, 404 when no such file exists,
     *         413 when the file is larger than {@link #MAX_BYTES}
     */
    @GET
    public Response image(@QueryParam("path") String path) {
        return serve(actorSystem == null ? null : actorSystem.getFileScope(), path);
    }

    /** The whole decision, apart from the injected actor system, so it can be tested on a real directory. */
    static Response serve(FileAccessScope scope, String path) {
        if (path == null || path.isBlank()) {
            return Response.status(400).entity("path required").build();
        }
        if (path.startsWith("~")) {
            return Response.status(400).entity("a path starting with ~ is not supported; write the absolute path").build();
        }
        String contentType = contentTypeOf(path);
        if (contentType == null) {
            return Response.status(400).entity("not an image file name").build();
        }
        if (scope == null) {
            return Response.status(403).entity("outside the readable range").build();
        }

        java.nio.file.Path real;
        try {
            java.nio.file.Path given = java.nio.file.Path.of(path);
            // A relative path is taken from the write directory, the same as for the read tool.
            real = (given.isAbsolute() ? given : scope.writeRoot().resolve(given)).toRealPath();
        } catch (java.nio.file.InvalidPathException e) {
            return Response.status(400).entity("invalid path").build();
        } catch (IOException e) {
            return Response.status(404).entity("no such file").build();
        }
        if (!Files.isRegularFile(real)) {
            return Response.status(404).entity("no such file").build();
        }
        if (!scope.canRead(real)) {
            return Response.status(403)
                    .entity("outside the readable range: " + scope.describeReadRoots())
                    .build();
        }

        byte[] bytes;
        try {
            if (Files.size(real) > MAX_BYTES) {
                return Response.status(413).entity("the image is larger than " + (MAX_BYTES / (1024 * 1024)) + " MiB").build();
            }
            bytes = Files.readAllBytes(real);
        } catch (IOException e) {
            return Response.status(404).entity("could not read the file").build();
        }
        // No caching: a conversation that overwrites a picture and shows it again must show the new
        // one, and the URL carries no version to distinguish them. The sandbox policy matters when
        // the address is opened in a tab of its own: an SVG is then a document, and without it the
        // scripts inside would run on this server's origin.
        return Response.ok(bytes, contentType)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", CSP)
                .build();
    }

    /** Applied to every served image: no script, no sub-resource, no origin of its own. */
    static final String CSP = "sandbox; default-src 'none'; style-src 'unsafe-inline'";

    /** @return the content type for the file name's extension, or {@code null} for any other name */
    static String contentTypeOf(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) return null;
        return CONTENT_TYPES.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
