package com.scivicslab.chatui.core.plugin;

import java.util.List;

/**
 * Where a workflow YAML's actors come from, for the Actions tab to list them without a run
 * ({@code ActionCatalogWithJavadoc_260930_oo01}).
 *
 * <p>A workflow run builds an actor system of its own and registers actors in it — the outer
 * workflow's {@code harness} and {@code queue}, the agent loop's {@code agent} — and tears it down
 * when the run ends, so there is no live system to ask between runs. Whoever registers actors for a
 * run implements this and answers the same names and classes its registration uses; a plugin jar
 * that registers actors of its own does the same, as an {@code @ApplicationScoped} bean, and the
 * catalog collects every implementation.</p>
 */
public interface WorkflowActorSource {

    /**
     * One actor a workflow may name.
     *
     * @param name   the name the YAML writes under {@code actor:}
     * @param type   the class whose {@code @Action} methods are the actor's actions
     * @param origin which kind of run registers it: {@code workflow} for the outer workflow run,
     *               {@code agent-loop} for the openai-compat provider's per-turn run; the catalog adds
     *               {@code application} for the actors alive in the application itself
     */
    record WorkflowActor(String name, Class<?> type, String origin) {}

    /** @return the actors this source's runs register, in the order they are registered */
    List<WorkflowActor> workflowActors();
}
