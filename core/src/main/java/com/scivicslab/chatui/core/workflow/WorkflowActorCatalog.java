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
 *
 * <p>A fourth kind needs no declaration: while a run is in progress its actor system exists, and
 * whoever built it tells this catalog ({@link #runStarted}, {@link #runEnded}); every actor in that
 * system is listed under {@code running:<title> #<n>}, whoever registered it. The declared sources
 * answer between runs; the running systems answer for what is actually there.</p>
 */
@ApplicationScoped
public class WorkflowActorCatalog {

    @Inject
    Instance<WorkflowActorSource> sources;

    @Inject
    ChatUiActorSystem chatSystem;

    /** One run in progress: its title, a number telling two runs of the same title apart, its system. */
    public record Run(int id, String title, com.scivicslab.turingworkflow.workflow.IIActorSystem system) {
        /** The origin its rows carry. */
        public String origin() { return "running:" + title + " #" + id; }
    }

    private final java.util.concurrent.atomic.AtomicInteger runSeq = new java.util.concurrent.atomic.AtomicInteger();
    private final Map<Integer, Run> running = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Tells the catalog a run's actor system exists, so its actors are listed while it runs.
     *
     * @param title  the run's name, as the tab shows it
     * @param system the run's actor system, already holding its actors or about to
     * @return the handle to give {@link #runEnded}
     */
    public Run runStarted(String title, com.scivicslab.turingworkflow.workflow.IIActorSystem system) {
        Run run = new Run(runSeq.incrementAndGet(), title == null ? "" : title, system);
        running.put(run.id(), run);
        return run;
    }

    /** Tells the catalog the run is over; its rows disappear. */
    public void runEnded(Run run) {
        if (run != null) running.remove(run.id());
    }

    /** The runs in progress, oldest first. */
    public List<Run> runs() {
        List<Run> runs = new ArrayList<>(running.values());
        runs.sort(java.util.Comparator.comparingInt(Run::id));
        return runs;
    }

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
        for (Run run : runs()) {
            for (String name : new java.util.TreeSet<>(run.system().listActorNames())) {
                Class<?> type = classIn(run.system(), name);
                rows.add(row(name, type == null ? "?" : type.getSimpleName(), run.origin()));
            }
        }
        if (chatSystem != null) {
            ActorNode tree = chatSystem.getActorTree();
            for (ActorNode node : tree.children()) {
                rows.add(row(node.name(), node.type(), "application"));
            }
        }
        return rows;
    }

    /**
     * The class whose {@code @Action} methods answer for one actor of a run's system: the reference
     * itself when it dispatches by action name ({@code IIActorRef}), else the object it holds.
     */
    private static Class<?> classIn(com.scivicslab.turingworkflow.workflow.IIActorSystem system, String name) {
        com.scivicslab.pojoactor.core.ActorRef<?> ref = system.getActor(name);
        if (ref == null) return null;
        if (ref instanceof com.scivicslab.pojoactor.action.CallableByActionName) return ref.getClass();
        try {
            @SuppressWarnings("unchecked")
            com.scivicslab.pojoactor.core.ActorRef<Object> r = (com.scivicslab.pojoactor.core.ActorRef<Object>) ref;
            return r.askNow(o -> (Class<?>) o.getClass()).get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
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
        if (origin != null && origin.startsWith("running:")) {
            for (Run run : runs()) {
                if (run.origin().equals(origin)) return classIn(run.system(), name);
            }
            return null;
        }
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
        if (sources == null) return actors;
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
