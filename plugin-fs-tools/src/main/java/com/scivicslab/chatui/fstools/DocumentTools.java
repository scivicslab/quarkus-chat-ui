package com.scivicslab.chatui.fstools;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * MCP tool for extracting text from documents via Apache Tika.
 * Supports PDF, Word, Excel, PowerPoint, HTML, and many other formats.
 */
@ApplicationScoped
public class DocumentTools {

    private static final Logger logger = Logger.getLogger(DocumentTools.class.getName());
    private static final int DEFAULT_MAX_LENGTH = 10000;
    private static final int EXTRACT_LIMIT = 2_000_000;

    private static final Tika tika = new Tika();

    @Inject
    FilesystemGuard guard;

    @Tool(description = "Extract text content from a local document file. "
            + "Supports PDF, Word (.docx/.doc), Excel (.xlsx/.xls), PowerPoint (.pptx/.ppt), "
            + "plain text, HTML, and many other formats via Apache Tika. "
            + "Use offset to read subsequent pages of large documents.")
    String read_document(
            @ToolArg(description = "Absolute path to the document file") String path,
            @ToolArg(description = "Character offset to start reading from (default 0)", required = false) Integer offset,
            @ToolArg(description = "Maximum characters to return (default 10000)", required = false) Integer max_length) {

        if (path == null || path.isBlank()) return "Error: 'path' argument is required";
        int start = (offset != null && offset > 0) ? offset : 0;
        int limit = (max_length != null && max_length > 0) ? max_length : DEFAULT_MAX_LENGTH;

        Optional<String> err = guard.check(path);
        if (err.isPresent()) return err.get();

        Path p = Path.of(path);
        if (!Files.exists(p)) return "Error: file not found: " + path;
        if (!Files.isReadable(p)) return "Error: file not readable: " + path;

        try {
            logger.info("Extracting text from: " + path + " (offset=" + start + ")");
            Metadata metadata = new Metadata();
            String fullText;
            try (InputStream is = new FileInputStream(p.toFile())) {
                fullText = tika.parseToString(is, metadata, EXTRACT_LIMIT);
            }

            int totalChars = fullText.length();
            int from = Math.min(start, totalChars);
            int to = Math.min(from + limit, totalChars);
            String chunk = fullText.substring(from, to);

            StringBuilder sb = new StringBuilder();
            if (start == 0) {
                String title = metadata.get(TikaCoreProperties.TITLE);
                String contentType = metadata.get(Metadata.CONTENT_TYPE);
                if (title != null && !title.isBlank()) sb.append("Title: ").append(title).append("\n");
                if (contentType != null) sb.append("Type: ").append(contentType).append("\n");
                if (!sb.isEmpty()) sb.append("\n");
            }

            sb.append(chunk);
            sb.append("\n\n[total_chars: ").append(totalChars)
              .append(", offset: ").append(from)
              .append(", returned: ").append(chunk.length());
            if (to < totalChars) {
                sb.append(", next_offset: ").append(to);
            } else {
                sb.append(", end_of_document: true");
            }
            sb.append("]");

            return sb.toString();
        } catch (Exception e) {
            logger.warning("Tika extraction failed for " + path + ": " + e.getMessage());
            return "Error reading " + path + ": " + e.getMessage();
        }
    }
}
