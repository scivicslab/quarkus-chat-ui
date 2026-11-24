package com.scivicslab.chatui.core.actor;

import com.scivicslab.chatui.core.provider.LlmProvider;
import com.scivicslab.pojoactor.core.ActorRef;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The model, effort and temperature of a conversation are set at once while its provider's mailbox is held by a running stream
 * (a PUT during a long answer), and the next request reads what was set.
 */
class ProviderSettingsDuringAStreamTest {

    @Test
    void theSettersReturnAtOnceWhileTheProviderIsBusyAndTheProviderHoldsTheNewValues() throws Exception {
        ChatUiActorSystem system = StartedSystem.start();
        ActorRef<LlmProvider> providerRef = system.getProviderRef("project1", "01");
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // A message that holds the provider's mailbox, as a running stream does.
        providerRef.tell(p -> {
            busy.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(busy.await(10, TimeUnit.SECONDS));
        try {
            long start = System.nanoTime();
            assertTrue(system.setModel("project1", "01", "gpt-test"));
            assertTrue(system.setTemperature("project1", "01", 0.25));
            assertTrue(system.setEffort("project1", "01", "low"));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue(millis < 1000, "the three setters took " + millis + " ms behind a running stream");

            assertEquals("gpt-test", system.getChatSession("project1", "01").getModelDirect());
            assertEquals(0.25, system.getChatSession("project1", "01").getTemperatureDirect());
        } finally {
            release.countDown();
        }
    }
}
