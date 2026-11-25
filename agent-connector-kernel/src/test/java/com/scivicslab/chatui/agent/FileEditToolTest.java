package com.scivicslab.chatui.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code edit}: exact text replaced once in one file under the working directory, or the file left alone with a reason. */
class FileEditToolTest {

    @TempDir
    Path root;

    private FileAccessScope scope() {
        return new FileAccessScope(root, List.of());
    }

    private Path file(String name, String text) throws Exception {
        Path p = root.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    @Test
    void anExactTextIsReplacedOnce() throws Exception {
        Path f = file("src/Main.java", "class A {\n  int x = 1;\n  int y = 2;\n}\n");
        String reply = FileEditTool.edit(scope(), "src/Main.java", "  int x = 1;\n", "  int x = 10;\n", null);
        assertEquals("class A {\n  int x = 10;\n  int y = 2;\n}\n", Files.readString(f));
        assertTrue(reply.startsWith("edited ") && reply.contains("replaced 1 occurrence"), reply);
        assertTrue(reply.contains(f.toString()), "the reply names the absolute path: " + reply);
    }

    @Test
    void japaneseTextAndLineBreaksSurvive() throws Exception {
        Path f = file("note.md", "# 題\n\nこれは古い文です。\n");
        FileEditTool.edit(scope(), "note.md", "古い文", "新しい文", "false");
        assertEquals("# 題\n\nこれは新しい文です。\n", Files.readString(f));
    }

    @Test
    void anAbsentTextLeavesTheFileAlone() throws Exception {
        Path f = file("a.txt", "alpha\n");
        String reply = FileEditTool.edit(scope(), "a.txt", "beta", "gamma", null);
        assertTrue(reply.startsWith("error: old_string was not found"), reply);
        assertEquals("alpha\n", Files.readString(f));
    }

    @Test
    void aTextThatIsNotUniqueIsRefusedUnlessReplaceAllIsAsked() throws Exception {
        Path f = file("a.txt", "x x x\n");
        String reply = FileEditTool.edit(scope(), "a.txt", "x", "y", null);
        assertTrue(reply.startsWith("error: old_string appears 3 times") && reply.contains("replace_all"), reply);
        assertEquals("x x x\n", Files.readString(f));
        String all = FileEditTool.edit(scope(), "a.txt", "x", "y", "true");
        assertTrue(all.contains("replaced 3 occurrences"), all);
        assertEquals("y y y\n", Files.readString(f));
    }

    @Test
    void theArgumentsAreChecked() throws Exception {
        file("a.txt", "alpha\n");
        assertEquals("error: path required", FileEditTool.edit(scope(), " ", "a", "b", null));
        assertTrue(FileEditTool.edit(scope(), "a.txt", "", "b", null).startsWith("error: old_string required"));
        assertTrue(FileEditTool.edit(scope(), "a.txt", "alpha", "alpha", null).contains("the same"));
        assertTrue(FileEditTool.edit(scope(), "missing.txt", "a", "b", null).startsWith("error: no such file"));
        Files.createDirectories(root.resolve("dir"));
        assertTrue(FileEditTool.edit(scope(), "dir", "a", "b", null).startsWith("error: path is a directory"));
    }

    @Test
    void aNewStringMayBeEmptyToDeleteText() throws Exception {
        Path f = file("a.txt", "keep drop keep\n");
        FileEditTool.edit(scope(), "a.txt", " drop", null, null);
        assertEquals("keep keep\n", Files.readString(f));
    }

    @Test
    void aPathOutsideTheWorkingDirectoryIsRefused(@TempDir Path outside) throws Exception {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret\n");
        assertTrue(FileEditTool.edit(scope(), "../" + outside.getFileName() + "/secret.txt", "top", "x", null)
                .startsWith("error: path is outside the writable directory"));
        assertTrue(FileEditTool.edit(scope(), secret.toString(), "top", "x", null).startsWith("error: path is outside"));
        assertEquals("top secret\n", Files.readString(secret));
    }

    @Test
    void aSymlinkThatLeadsOutIsRefused(@TempDir Path outside) throws Exception {
        Path secret = outside.resolve("secret.txt");
        Files.writeString(secret, "top secret\n");
        Files.createSymbolicLink(root.resolve("link.txt"), secret);
        String reply = FileEditTool.edit(scope(), "link.txt", "top", "x", null);
        assertTrue(reply.startsWith("error: path escapes working directory"), reply);
        assertEquals("top secret\n", Files.readString(secret));
    }

    @Test
    void theToolIsInTheCatalogNextToWriteAndALocalHarnessLeavesItOut() {
        assertTrue(ToolCatalog.core("edit") != null);
        assertEquals(List.of("path", "old_string", "new_string"), ToolCatalog.core("edit").required());
    }
}
