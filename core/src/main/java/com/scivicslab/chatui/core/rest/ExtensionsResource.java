package com.scivicslab.chatui.core.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Returns installed Claude Code extensions:
 * Skills (~/.claude/commands/), Agents (~/.claude/agents/),
 * Hooks (~/.claude/settings.json), MCP Servers (~/.claude/mcp-configs/).
 */
@Path("/api/extensions")
@Blocking
public class ExtensionsResource {

    private static final Logger LOG = Logger.getLogger(ExtensionsResource.class.getName());

    @Inject
    ObjectMapper mapper;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ExtensionsInfo extensions() {
        String home = System.getProperty("user.home");
        return new ExtensionsInfo(
            loadMarkdownEntries(home + "/.claude/commands"),
            loadMarkdownEntries(home + "/.claude/agents"),
            loadHooks(home + "/.claude/settings.json"),
            loadMcpServers(home + "/.claude/mcp-configs/mcp-servers.json")
        );
    }

    /** Returns the raw Markdown content of a single skill or agent file. */
    @GET
    @Path("/content")
    @Produces(MediaType.TEXT_PLAIN)
    public Response content(@QueryParam("type") String type, @QueryParam("name") String name) {
        // Security: reject names that could escape the target directory
        if (name == null || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return Response.status(400).entity("Invalid name").build();
        }
        String home = System.getProperty("user.home");
        String dirPath;
        if ("skills".equals(type)) {
            dirPath = home + "/.claude/commands";
        } else if ("agents".equals(type)) {
            dirPath = home + "/.claude/agents";
        } else {
            return Response.status(400).entity("Invalid type").build();
        }
        var file = Paths.get(dirPath).resolve(name + ".md");
        if (!Files.exists(file)) {
            return Response.status(404).entity("Not found").build();
        }
        try {
            return Response.ok(Files.readString(file)).build();
        } catch (IOException e) {
            LOG.warning("Cannot read " + file + ": " + e.getMessage());
            return Response.serverError().entity("Error reading file").build();
        }
    }

    // ── Skills & Agents ───────────────────────────────────────────────────────

    private List<Entry> loadMarkdownEntries(String dirPath) {
        var result = new ArrayList<Entry>();
        var dir = Paths.get(dirPath);
        if (!Files.isDirectory(dir)) return result;

        try (Stream<java.nio.file.Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .sorted()
                 .forEach(p -> {
                     String name = p.getFileName().toString().replaceAll("\\.md$", "");
                     String desc = extractDescription(p);
                     result.add(new Entry(name, desc));
                 });
        } catch (IOException e) {
            LOG.warning("Cannot list " + dirPath + ": " + e.getMessage());
        }
        return result;
    }

    /** Extracts the `description:` value from YAML frontmatter, or falls back to first non-blank line. */
    private String extractDescription(java.nio.file.Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            boolean inFrontMatter = false;
            for (String line : lines) {
                String stripped = line.strip();
                if (stripped.equals("---")) {
                    inFrontMatter = !inFrontMatter;
                    continue;
                }
                if (inFrontMatter && stripped.startsWith("description:")) {
                    return stripped.substring("description:".length()).strip();
                }
            }
            // Fallback: first non-empty, non-frontmatter, non-heading line
            for (String line : lines) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !stripped.equals("---") && !stripped.startsWith("#")) {
                    return stripped.length() > 100 ? stripped.substring(0, 100) + "…" : stripped;
                }
            }
        } catch (IOException e) {
            LOG.fine("Cannot read " + file + ": " + e.getMessage());
        }
        return "";
    }

    // ── Hooks ─────────────────────────────────────────────────────────────────

    private List<HookEntry> loadHooks(String settingsPath) {
        var result = new ArrayList<HookEntry>();
        var file = Paths.get(settingsPath);
        if (!Files.exists(file)) return result;

        try {
            JsonNode root = mapper.readTree(file.toFile());
            JsonNode hooks = root.path("hooks");
            if (hooks.isMissingNode() || hooks.isNull()) return result;

            hooks.fields().forEachRemaining(eventEntry -> {
                String event = eventEntry.getKey();
                JsonNode hookList = eventEntry.getValue();
                if (hookList.isArray()) {
                    for (JsonNode hook : hookList) {
                        String matcher = hook.path("matcher").asText("");
                        JsonNode cmds = hook.path("hooks");
                        if (cmds.isArray()) {
                            for (JsonNode cmd : cmds) {
                                String command = cmd.path("command").asText("");
                                if (!command.isBlank()) {
                                    // Shorten long commands for display
                                    String display = command.length() > 80
                                        ? command.substring(0, 80) + "…" : command;
                                    result.add(new HookEntry(event, matcher, display));
                                }
                            }
                        }
                    }
                }
            });
        } catch (IOException e) {
            LOG.warning("Cannot read settings.json: " + e.getMessage());
        }
        return result;
    }

    // ── MCP Servers ───────────────────────────────────────────────────────────

    private List<McpEntry> loadMcpServers(String configPath) {
        var result = new ArrayList<McpEntry>();
        var file = Paths.get(configPath);
        if (!Files.exists(file)) return result;

        try {
            JsonNode root = mapper.readTree(file.toFile());
            JsonNode servers = root.path("mcpServers");
            if (servers.isMissingNode() || servers.isNull()) return result;

            servers.fields().forEachRemaining(e -> {
                String name = e.getKey();
                JsonNode cfg = e.getValue();
                String desc = cfg.path("description").asText("");
                String type = cfg.has("url") ? "http" : "stdio";
                String endpoint = cfg.has("url")
                    ? cfg.path("url").asText("")
                    : cfg.path("command").asText("") + " " + joinArgs(cfg.path("args"));
                result.add(new McpEntry(name, desc, type, endpoint.strip()));
            });
        } catch (IOException e) {
            LOG.warning("Cannot read mcp-servers.json: " + e.getMessage());
        }
        return result;
    }

    private String joinArgs(JsonNode args) {
        if (!args.isArray()) return "";
        var sb = new StringBuilder();
        for (JsonNode a : args) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(a.asText());
        }
        return sb.toString();
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record Entry(String name, String description) {}
    public record HookEntry(String event, String matcher, String command) {}
    public record McpEntry(String name, String description, String type, String endpoint) {}
    public record ExtensionsInfo(
        List<Entry> skills,
        List<Entry> agents,
        List<HookEntry> hooks,
        List<McpEntry> mcpServers
    ) {}
}
