package com.scivicslab.chatui.openaicompat;

import com.scivicslab.chatui.openaicompat.client.HttpCallbacks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The callbacks pool's real threads are not daemons, so they must not stay once the pool is idle: a plain JVM would not exit. */
class HttpCallbacksTest {

    @Test
    void idleThreadsOfTheCallbacksPoolEndAndTheNextTaskMakesNewOnes() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        HttpCallbacks.POOL.execute(ran::countDown);
        assertTrue(ran.await(5, TimeUnit.SECONDS));
        assertTrue(HttpCallbacks.POOL.getPoolSize() >= 1, "a thread was made for the task");

        long until = System.currentTimeMillis() + 10_000;
        while (HttpCallbacks.POOL.getPoolSize() > 0 && System.currentTimeMillis() < until) Thread.sleep(100);
        assertEquals(0, HttpCallbacks.POOL.getPoolSize(), "the idle threads ended");

        CountDownLatch again = new CountDownLatch(1);
        HttpCallbacks.POOL.execute(again::countDown);
        assertTrue(again.await(5, TimeUnit.SECONDS), "the pool still works");
    }
}
