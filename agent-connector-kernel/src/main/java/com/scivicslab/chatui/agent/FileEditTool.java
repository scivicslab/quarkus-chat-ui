package com.scivicslab.chatui.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * The {@code edit} tool: replaces exact text in one existing file under the working directory. It is what a model reaches
 * for to change a few lines, so it need not rewrite the whole file with {@code write}.
 *
 * <p>The text to replace must be there, and there once: when {@code old_string} is absent or appears more than once the
 * file is left alone and the answer says so, unless {@code replace_all} asks for every occurrence. The path is confined as
 * {@link FileWriteTool}'s is: it must stay inside the writable directory, and the file's real path is checked after the
 * normalized one, so a symlink cannot lead out.</p>
 */
public final class FileEditTool {

    private FileEditTool() {}

    /**
     * @param scope      the conversation's file range
     * @param path       the file, as the model wrote it
     * @param oldString  the text to replace, exactly
     * @param newString  what goes in its place
     * @param replaceAll {@code "true"} to replace every occurrence; anything else requires {@code oldString} to occur once
     * @return what was done, or an {@code error: ...} the conversation feeds back as its observation
     */
    public static String edit(FileAccessScope scope, String path, String oldString, String newString, String replaceAll) {
        if (path == null || path.isBlank()) return "error: path required";
        if (oldString == null || oldString.isEmpty()) return "error: old_string required (the exact text to replace)";
        String replacement = newString == null ? "" : newString;
        if (oldString.equals(replacement)) return "error: old_string and new_string are the same, so nothing would change";
        try {
            Path base = scope.writeRoot();
            Path target = base.resolve(FileReadTool.expandHome(path.trim())).normalize();
            if (!scope.canWrite(target)) {
                return "error: path is outside the writable directory (" + base + "): " + path;
            }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.exists(target)) return "error: no such file: " + path;
            if (Files.isDirectory(target)) return "error: path is a directory: " + path;
            if (!target.toRealPath().startsWith(base.toRealPath())) return "error: path escapes working directory: " + path;
            String text = Files.readString(target, StandardCharsets.UTF_8);
            int count = 0;
            for (int at = text.indexOf(oldString); at >= 0; at = text.indexOf(oldString, at + oldString.length())) count++;
            if (count == 0) return "error: old_string was not found in " + path + ". Read the file and copy the text exactly, "
                    + "including its spaces and line breaks.";
            boolean all = "true".equalsIgnoreCase(replaceAll == null ? "" : replaceAll.strip());
            if (count > 1 && !all) {
                return "error: old_string appears " + count + " times in " + path + ". Add the lines around it so it is "
                        + "unique, or pass replace_all=true to replace every one.";
            }
            Files.writeString(target, text.replace(oldString, replacement), StandardCharsets.UTF_8);
            return "edited " + target + " (replaced " + count + " occurrence" + (count == 1 ? "" : "s") + ")";
        } catch (IOException e) {
            return "error: " + e.getMessage();
        }
    }
}
