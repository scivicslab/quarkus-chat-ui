package com.scivicslab.chatui.audittrail;

import com.scivicslab.chatui.agent.FileAccessScope;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit test for which file names {@link LocalImageResource} will serve. No HTTP, no file
 * system: the decision is made from the name alone, before anything is opened.
 *
 * <p>The list is fixed rather than probed from the bytes, so a name the list does not cover is
 * refused. That refusal is the point being held in place here: without it the endpoint would hand
 * out any readable file under whatever content type a caller could talk it into.</p>
 */
class LocalImageResourceTest {

    @Test
    void theImageTypesAreServedUnderTheirOwnContentType() {
        assertEquals("image/png", LocalImageResource.contentTypeOf("/home/devteam/works/shot.png"));
        assertEquals("image/jpeg", LocalImageResource.contentTypeOf("/tmp/a.jpg"));
        assertEquals("image/jpeg", LocalImageResource.contentTypeOf("/tmp/a.jpeg"));
        assertEquals("image/gif", LocalImageResource.contentTypeOf("/tmp/a.gif"));
        assertEquals("image/webp", LocalImageResource.contentTypeOf("/tmp/a.webp"));
        assertEquals("image/svg+xml", LocalImageResource.contentTypeOf("/tmp/a.svg"));
    }

    /** The extension is written both ways; the same file must be served either way. */
    @Test
    void theExtensionIsReadWithoutCase() {
        assertEquals("image/png", LocalImageResource.contentTypeOf("/home/devteam/works/SHOT.PNG"));
        assertEquals("image/jpeg", LocalImageResource.contentTypeOf("/home/devteam/works/Photo.JPG"));
    }

    /** Anything that is not one of the image names is refused before the file is opened. */
    @Test
    void anyOtherNameIsRefused() {
        assertNull(LocalImageResource.contentTypeOf("/etc/passwd"));
        assertNull(LocalImageResource.contentTypeOf("/home/devteam/works/notes.md"));
        assertNull(LocalImageResource.contentTypeOf("/home/devteam/.ssh/id_rsa"));
        assertNull(LocalImageResource.contentTypeOf("/home/devteam/works/archive.png.gz"));
        assertNull(LocalImageResource.contentTypeOf("/tmp/a.bmp"));
        assertNull(LocalImageResource.contentTypeOf("noextension"));
        assertNull(LocalImageResource.contentTypeOf("/tmp/trailing."));
    }

    // ---- serve(): the decision on a real directory ----

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    private static FileAccessScope scope(Path root) {
        return new FileAccessScope(root, List.of());
    }

    @Test
    void anImageInsideTheReadRootIsServedWithTheProtectiveHeaders(@TempDir Path root) throws IOException {
        Files.write(root.resolve("a.png"), PNG);
        Response r = LocalImageResource.serve(scope(root), root.resolve("a.png").toString());
        assertEquals(200, r.getStatus());
        assertEquals("image/png", r.getMediaType().toString());
        assertEquals("nosniff", r.getHeaderString("X-Content-Type-Options"));
        assertEquals("no-store", r.getHeaderString("Cache-Control"));
        assertEquals(LocalImageResource.CSP, r.getHeaderString("Content-Security-Policy"));
    }

    @Test
    void aRelativePathIsTakenFromTheWriteDirectory(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("sub"));
        Files.write(root.resolve("sub/a.png"), PNG);
        assertEquals(200, LocalImageResource.serve(scope(root), "sub/a.png").getStatus());
    }

    @Test
    void aPathStartingWithTildeIsRefused(@TempDir Path root) {
        assertEquals(400, LocalImageResource.serve(scope(root), "~/a.png").getStatus());
    }

    @Test
    void aFileOutsideTheReadRootIsForbiddenAndTheReasonNamesTheRoots(@TempDir Path root, @TempDir Path other) throws IOException {
        Files.write(other.resolve("o.png"), PNG);
        Response r = LocalImageResource.serve(scope(root), other.resolve("o.png").toString());
        assertEquals(403, r.getStatus());
        assertTrue(String.valueOf(r.getEntity()).contains(root.toString()));
    }

    @Test
    void dotDotAndASymlinkCannotLeaveTheReadRoot(@TempDir Path root, @TempDir Path other) throws IOException {
        Files.write(other.resolve("o.png"), PNG);
        Files.createSymbolicLink(root.resolve("link.png"), other.resolve("o.png"));
        assertEquals(403, LocalImageResource.serve(scope(root), root.resolve("link.png").toString()).getStatus());
        String viaDotDot = root + "/../" + other.getFileName() + "/o.png";
        Response r = LocalImageResource.serve(scope(root), viaDotDot);
        assertTrue(r.getStatus() == 403 || r.getStatus() == 404, "status " + r.getStatus());
    }

    @Test
    void aMissingFileAndADirectoryAre404(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("d.png"));
        assertEquals(404, LocalImageResource.serve(scope(root), root.resolve("none.png").toString()).getStatus());
        assertEquals(404, LocalImageResource.serve(scope(root), root.resolve("d.png").toString()).getStatus());
    }

    @Test
    void aFileOverTheLimitIs413(@TempDir Path root) throws IOException {
        Path big = root.resolve("big.png");
        try (RandomAccessFile f = new RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(LocalImageResource.MAX_BYTES + 1);   // sparse: no real disk use
        }
        assertEquals(413, LocalImageResource.serve(scope(root), big.toString()).getStatus());
    }

    @Test
    void aNonImageNameIsRefusedBeforeTheFileIsLookedAt(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("notes.md"), "x");
        assertEquals(400, LocalImageResource.serve(scope(root), root.resolve("notes.md").toString()).getStatus());
        assertEquals(400, LocalImageResource.serve(scope(root), "a\0.png").getStatus());
    }
}
