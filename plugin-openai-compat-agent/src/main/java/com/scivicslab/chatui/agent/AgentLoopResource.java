package com.scivicslab.chatui.agent;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** REST behind the Agent Loop tab: which inner-loop workflow runs, its YAML, and switching it. */
@Path("/api/agent-loop")
@Produces(MediaType.APPLICATION_JSON)
public class AgentLoopResource {

    @Inject
    AgentLoopExtensionImpl loop;

    @ConfigProperty(name = "chat-ui.provider", defaultValue = "claude")
    String providerName;

    private boolean active() {
        return "openai-compat".equalsIgnoreCase(providerName.trim()) && loop.isEnabled();
    }

    @GET
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", providerName);
        m.put("enabled", active());
        m.put("workflow", active() ? loop.currentWorkflow() : null);
        return m;
    }

    @GET
    @Path("/workflows")
    public List<Map<String, Object>> workflows() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : AgentLoopExtensionImpl.BUNDLED) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("title", name);
            m.put("current", name.equals(loop.currentWorkflow()));
            out.add(m);
        }
        return out;
    }

    @GET
    @Path("/workflows/{name}")
    public Response workflow(@PathParam("name") String name) {
        String yaml = AgentLoopRun.readBundledYaml(name);
        if (yaml == null) return Response.status(404).entity(Map.of("error", "unknown workflow: " + name)).build();
        return Response.ok(Map.of("name", name, "yaml", yaml)).build();
    }

    public static class SelectRequest {
        public String name;
    }

    @POST
    @Path("/workflow")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response select(SelectRequest req) {
        String name = req == null || req.name == null ? "" : req.name.trim();
        String error = loop.selectWorkflow(name);
        if (error != null) return Response.status(400).entity(Map.of("error", error)).build();
        return Response.ok(Map.of("type", "ok", "workflow", name)).build();
    }
}
