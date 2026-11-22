package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.agent.FileAccessScope;
import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.chatui.core.provider.ProviderContext;
import com.scivicslab.chatui.core.rest.ChatEvent;
import com.scivicslab.pojoactor.core.ActorRef;
import com.scivicslab.turingworkflow.workflow.IIActorSystem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The workflows the app runs read files by path only under their base directory: a project's job under the project's
 * working directory, a conversation's agent loop under the directory its harness works in (the project's working
 * directory, else the write root). Each case runs a workflow, the way the app does, whose step calls
 * {@code this.runWorkflow} on a file outside that directory and on one inside it; the file puts a mark in the runner's
 * state when it is read and run.
 *
 * <p>The limit is Turing-workflow's ({@code PathUnderBase}, with the base directory the app sets). Built against a
 * Turing-workflow without it, an interpreter reads any file, so these cases are skipped there rather than failing.</p>
 */
class WorkflowBaseDirTest {

    @BeforeAll
    static void needsTheLimit() {
        boolean present;
        try {
            Class.forName("com.scivicslab.turingworkflow.workflow.PathUnderBase");
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        assumeTrue(present, "this Turing-workflow has no base-directory limit (PathUnderBase)");
    }

    /** A workflow that, when read and run, puts {@code mark: read} into the state of whoever ran it. */
    private static Path markingWorkflow(Path dir, String mark) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(mark + ".yaml");
        Files.writeString(file, """
                name: %s
                steps:
                  - states: ["0", "end"]
                    actions:
                      - actor: this
                        method: putJson
                        arguments: {path: %s, value: read}
                        execution: direct
                """.formatted(mark, mark));
        return file;
    }

    /** Runs {@code file} with {@code this.runWorkflow} from state {@code first}; a refusal also ends the run. */
    private static String readingWorkflow(String first, Path file) {
        return """
                name: reads-a-file
                steps:
                  - states: ["%1$s", "end"]
                    label: read-it
                    actions:
                      - actor: this
                        method: runWorkflow
                        arguments: ["%2$s", 10]
                        execution: direct
                  - states: ["%1$s", "end"]
                    label: refused
                    actions:
                      - actor: this
                        method: doNothing
                        execution: direct
                """.formatted(first, file.toString());
    }

    private static boolean marked(com.scivicslab.turingworkflow.workflow.IIActorRef<?> actor, String mark) {
        var r = actor.callByActionName("getJson", "[\"" + mark + "\"]");
        return r.isSuccess() && r.getResult() != null && r.getResult().contains("read");
    }

    private static Project.JobView await(ActorRef<Project> ref, String jobId) throws Exception {
        for (int i = 0; i < 400; i++) {
            Project.JobView job = ref.ask(p -> p.job(jobId)).join();
            if (job != null && (Project.FINISHED.equals(job.state()) || Project.FAILED.equals(job.state()))) return job;
            Thread.sleep(25);
        }
        return ref.ask(p -> p.job(jobId)).join();
    }

    @Test
    void aProjectsJob_readsWorkflowFilesOnlyUnderTheProjectsWorkingDirectory(@TempDir Path tmp) throws Exception {
        Path project = Files.createDirectories(tmp.resolve("project"));
        Path inside = markingWorkflow(project.resolve("workflows"), "insideMark");
        Path outside = markingWorkflow(tmp.resolve("elsewhere"), "outsideMark");

        IIActorSystem system = new IIActorSystem("workflow-base-dir-job-test");
        try {
            system.actorOf("callWatchdog", new CallWatchdog());
            ActorRef<Project> ref = system.actorOf("project1", new Project());
            ref.tell(p -> p.bind("project1", system, ref, null)).join();
            String set = ref.ask(p -> p.setWorkingDir(project)).join();
            assertTrue(set.startsWith("ok:"), set);

            ref.ask(p -> p.submitJob("reads-outside", readingWorkflow("0", outside))).join();
            Project.JobView first = await(ref, "job-01");
            assertFalse(marked(system.getIIActor("project1/job-01"), "outsideMark"),
                    "a job did not read a workflow file outside the project's working directory; job: " + first);

            ref.ask(p -> p.submitJob("reads-inside", readingWorkflow("0", inside))).join();
            Project.JobView second = await(ref, "job-02");
            assertTrue(marked(system.getIIActor("project1/job-02"), "insideMark"),
                    "a job still reads a workflow file under the project's working directory; job: " + second);
        } finally {
            system.terminate();
        }
    }

    /** Answers every prompt at once; the agent loops here never call it. */
    private static final class SilentProvider implements LlmProvider {
        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake"; }
        @Override public List<ModelEntry> getAvailableModels() { return List.of(); }
        @Override public String getCurrentModel() { return "fake-model"; }
        @Override public void setModel(String model) {}
        @Override public void cancel() {}
        @Override
        public void sendPrompt(String prompt, String model, Consumer<ChatEvent> emitter, ProviderContext ctx) {
            emitter.accept(ChatEvent.delta("ok"));
            emitter.accept(ChatEvent.result(null, 0, 0));
        }
    }

    /** One turn of a conversation whose write root is {@code writeRoot}, under {@code agentLoop}. */
    private static ChatSessionIIAR runTurn(IIActorSystem system, Path writeRoot, String agentLoop) {
        SilentProvider provider = new SilentProvider();
        ChatSessionIIAR iiar = new ChatSessionIIAR("chat", provider, Optional.empty(), null, system);
        system.addIIActor(iiar);
        ActorRef<LlmProvider> providerRef = iiar.<LlmProvider>createChild(iiar.getName() + ".provider", provider);
        iiar.tellNow(a -> {
            ChatSession c = (ChatSession) a;
            c.setProviderName(providerRef.getName());
            c.setFileScope(new FileAccessScope(writeRoot, List.of()));
            c.reset();
            c.readYaml(new ByteArrayInputStream(agentLoop.getBytes(StandardCharsets.UTF_8)));
        }).join();
        ActorRef<ChatSession> chatRef = iiar.asChatSessionRef();
        try {
            chatRef.tellNow(c -> {
                c.start("read it", null, event -> {}, chatRef, new CompletableFuture<>(), null, false);
                c.runUntilEnd();
            }).join();
        } catch (java.util.concurrent.CompletionException e) {
            // A refused runWorkflow has already reset the interpreter that called it (Turing-workflow resets before
            // it checks the path), so the turn cannot go on; what matters here is only whether the file was read.
            System.out.println("the turn ended with " + e.getCause());
        }
        return iiar;
    }

    @Test
    void anAgentLoop_readsWorkflowFilesOnlyUnderTheConversationsDirectory(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("write-root"));
        Path inside = markingWorkflow(root.resolve("loops"), "insideMark");
        Path outside = markingWorkflow(tmp.resolve("elsewhere"), "outsideMark");

        IIActorSystem system = new IIActorSystem("workflow-base-dir-loop-test");
        try {
            ChatSessionIIAR outsideTurn = runTurn(system, root, readingWorkflow("think", outside));
            assertFalse(marked(outsideTurn, "outsideMark"),
                    "an agent loop did not read a workflow file outside the conversation's directory");
            assertEquals(root.toRealPath().toString(),
                    Path.of(outsideTurn.asChatSessionRef().ask(ChatSession::getWorkflowBaseDir).join()).toRealPath().toString(),
                    "the turn's base directory is the write root when there is no project directory");
        } finally {
            system.terminate();
        }

        IIActorSystem second = new IIActorSystem("workflow-base-dir-loop-test-2");
        try {
            ChatSessionIIAR insideTurn = runTurn(second, root, readingWorkflow("think", inside));
            assertTrue(marked(insideTurn, "insideMark"),
                    "an agent loop still reads a workflow file under the conversation's directory");
        } finally {
            second.terminate();
        }
    }
}
