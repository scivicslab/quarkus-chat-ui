package com.scivicslab.chatui.agent;

import com.scivicslab.chatui.core.actor.TurnCancel;
import com.scivicslab.chatui.core.provider.CancelScope;
import com.scivicslab.chatui.kernel.BlockingJob;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a {@code web_search} observation — ten results, each carrying a whole fetched page — into
 * one the model can hold, by summarising each page instead of keeping its opening characters.
 *
 * <p>Keeping the first 600 characters of a page was the tool's own guess at the turn's context
 * budget. It threw away up to 88% of a page already downloaded, and — because the I/O log records
 * what a tool returns — threw it away before the log ever saw it. The whole page now reaches both
 * the log and this class; only the model's copy is shortened, which is the same rule the loop
 * already applies to every other observation ({@code TurnResourceLimits_260830_oo01}).
 *
 * <p>The pages are summarised in parallel. One page's summary does not depend on another's, so
 * the wall-clock cost is one call rather than ten; the token cost is unchanged, and every one of
 * those calls is recorded, which is what this product exists to do.
 */
public final class WebPageSummarizer {

    private static final Logger LOG = Logger.getLogger(WebPageSummarizer.class.getName());

    /** Pages shorter than this are already short enough to pass through untouched. */
    static final int SUMMARIZE_ABOVE_CHARS = 1200;

    private final java.util.function.BiFunction<String, CancelScope, String> summarizeOne;

    /**
     * @param summarizeOne one LLM call: takes a page's text, returns its summary. Injected rather
     *                     than built here so a test can drive this class without a server.
     */
    public WebPageSummarizer(UnaryOperator<String> summarizeOne) {
        this.summarizeOne = (page, scope) -> summarizeOne.apply(page);
    }

    /**
     * @param summarizeOne one LLM call that ends when the scope it is given is cancelled (it registers what closes its
     *                     request there): takes a page's text, returns its summary
     */
    public WebPageSummarizer(java.util.function.BiFunction<String, CancelScope, String> summarizeOne) {
        this.summarizeOne = summarizeOne;
    }

    /** The instruction each page is summarised under. */
    public static String promptFor(String pageText) {
        return """
                Summarise the web page below for someone who asked a question and is waiting.
                Keep every fact, number, name and date that a reader might have wanted from the
                page; drop navigation, boilerplate and repetition. Write plain prose, no preamble.

                PAGE:
                """ + pageText;
    }

    /**
     * Replaces each result's page text with its summary, leaving the numbering, titles and URLs
     * as they are.
     *
     * @param observation what {@code web_search} returned: blocks starting with "1. ", "2. ", …
     * @return the same blocks with summarised bodies
     */
    public String summarize(String observation) {
        return summarize(observation, CancelScope.NEVER);
    }

    /**
     * As {@link #summarize(String)}; the page calls end at once, their requests closed, when {@code turnScope} is cancelled, when
     * the calling thread is interrupted, or when one of them fails (the observation is passed through then, so the others'
     * answers would be thrown away).
     */
    public String summarize(String observation, CancelScope turnScope) {
        List<String> blocks = splitBlocks(observation);
        // Leave anything that is not a result list alone ("No results found.", "error: ..."). One
        // result is still a result list — counting blocks instead of looking at the first one
        // silently skipped a single-hit search, which is exactly when the one page matters most.
        if (blocks.isEmpty() || !blocks.get(0).matches("(?s)\\d+\\. .*")) {
            return observation;
        }
        // One job (an actor of its own) per page, so the calls run in parallel; the blocks keep their order.
        List<BlockingJob<String>> summarised = new ArrayList<>();
        // The calls of this one observation end together: a cancel of this scope (from the turn, an interrupt or a failed
        // sibling) reaches every request that registered on it.
        TurnCancel scope = new TurnCancel("web-page-summaries");
        scope.begin();
        CancelScope.Registration inTurn = turnScope.register(scope::cancel);
        try {
            if (inTurn == null) return observation;   // the turn was cancelled already
            for (String block : blocks) {
                BlockingJob<String> job = BlockingJob.start("web-page-summary", () -> summarizeBlock(block, scope), scope::cancel);
                job.result().whenComplete((summary, failure) -> {
                    if (failure != null) scope.cancel();
                });
                summarised.add(job);
            }
            StringBuilder out = new StringBuilder();
            for (BlockingJob<String> job : summarised) {
                out.append(job.result().get()).append("\n\n");
            }
            return out.toString().stripTrailing();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scope.cancel();
            return observation;
        } catch (Exception e) {
            scope.cancel();
            LOG.log(Level.WARNING, "summarising a web_search observation failed; passing it through", e);
            return observation;
        } finally {
            if (inTurn != null) inTurn.close();
            scope.close();
        }
    }

    /**
     * Splits on the result numbering {@code web_search} writes. A line that starts a block is
     * "N. " at column zero; the page text it fetched is indented by three spaces, so an indented
     * line that happens to look like a numbered list does not split a block.
     */
    static List<String> splitBlocks(String observation) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : observation.split("\n", -1)) {
            if (line.matches("\\d+\\. .*") && current.length() > 0) {
                blocks.add(current.toString().stripTrailing());
                current.setLength(0);
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            blocks.add(current.toString().stripTrailing());
        }
        return blocks;
    }

    /** Keeps a block's first two lines (number/title, URL) and summarises the rest. */
    private String summarizeBlock(String block, CancelScope scope) {
        String[] lines = block.split("\n", -1);
        int bodyStart = (lines.length > 1 && lines[1].strip().startsWith("URL:")) ? 2 : 1;
        String head = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, bodyStart));
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, bodyStart, lines.length)).strip();

        if (body.length() <= SUMMARIZE_ABOVE_CHARS) {
            return block;
        }
        String summary = summarizeOne.apply(body, scope);
        if (summary == null || summary.isBlank()) {
            return block;   // a failed call must not lose the page
        }
        return head + "\n   " + summary.strip().replace("\n", "\n   ")
                + "\n   [summarised from " + body.length() + " chars]";
    }
}
