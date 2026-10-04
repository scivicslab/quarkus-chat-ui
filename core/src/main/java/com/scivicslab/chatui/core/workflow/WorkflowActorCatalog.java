package com.scivicslab.chatui.core.workflow;

import com.scivicslab.chatui.core.actor.ActorNode;
import com.scivicslab.chatui.core.actor.ChatUiActorSystem;
import com.scivicslab.chatui.core.plugin.WorkflowActorSource;
import com.scivicslab.chatui.core.plugin.WorkflowActorSource.WorkflowActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every actor the Actions tab can name, from every {@link WorkflowActorSource} and from the
 * application's own actor system ({@code ActionCatalogWithJavadoc_260930_oo01}).
 *
 * <p>Three origins. {@code workflow}: what an outer-workflow run registers ({@code harness},
 * {@code queue}, ...). {@code agent-loop}: what the openai-compat provider's per-turn run registers
 * ({@code agent}, ...), when that plugin is present. {@code application}: the actors alive in the
 * application now ({@code chat}, {@code queue}, ...), which a workflow cannot name but whose actions,
 * if any, are declared the same way. A name may appear under more than one origin — {@code queue}
 * is the workflow's {@code QueueBridgeIIAR} and the application's {@code QueueActor} — so a row is
 * identified by origin and name together.</p>
 */
@ApplicationScoped
public class WorkflowActorCatalog {

    @Inject
    Instance<WorkflowActorSource> sources;

    @Inject
    ChatUiActorSystem chatSystem;

    /** The order the upper pane shows origins in; an origin not listed here comes after these. */
    private static final List<String> ORIGIN_ORDER = List.of("workflow", "agent-loop");

    /**
     * The rows of the tab's upper pane: {@code {name, type, origin}}, the outer workflow's actors
     * first, then the agent loop's, then any other source's, then the live ones.
     */
    public List<Map<String, String>> rows() {
        List<Map<String, String>> rows = new ArrayList<>();
        List<WorkflowActor> fromSources = new ArrayList<>(sourceActors());
        fromSources.sort(java.util.Comparator.comparingInt(a -> {
            int i = ORIGIN_ORDER.indexOf(a.origin());
            return i < 0 ? ORIGIN_ORDER.size() : i;
        }));
        for (WorkflowActor a : fromSources) {
            rows.add(row(a.name(), a.type().getSimpleName(), a.origin()));
        }
        ActorNode tree = chatSystem.getActorTree();
        for (ActorNode node : tree.children()) {
            rows.add(row(node.name(), node.type(), "application"));
        }
        return rows;
    }

    /**
     * The class whose {@code @Action} methods answer for one row.
     *
     * @param origin the row's origin; null or empty means the first origin that has the name
     * @param name   the actor name
     * @return the class, or null when no row matches
     */
    public Class<?> classOf(String origin, String name) {
        if (name == null || name.isBlank()) return null;
        boolean any = origin == null || origin.isBlank();
        if (any || !origin.equals("application")) {
            for (WorkflowActor a : sourceActors()) {
                if (a.name().equals(name) && (any || a.origin().equals(origin))) return a.type();
            }
        }
        if (any || origin.equals("application")) {
            return chatSystem.actorClassOf(name);
        }
        return null;
    }

    private List<WorkflowActor> sourceActors() {
        List<WorkflowActor> actors = new ArrayList<>();
        for (WorkflowActorSource source : sources) {
            actors.addAll(source.workflowActors());
        }
        return actors;
    }

    private static Map<String, String> row(String name, String type, String origin) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("type", type);
        row.put("origin", origin);
        return row;
    }
}
