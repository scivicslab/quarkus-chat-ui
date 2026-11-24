package com.scivicslab.chatui.openaicompat.client;

import com.scivicslab.pojoactor.core.ManagedThreadPool;

/**
 * The real threads the JDK {@code HttpClient}s of the local LLM client and the MCP client run their short callbacks on
 * (TLS tasks, completions). One {@link ManagedThreadPool} shared by all of them, so no client makes a thread pool of its
 * own; threads are made on first use. A request itself blocks its caller's virtual or pool thread, not these.
 */
public final class HttpCallbacks {

    /** The shared pool. */
    public static final ManagedThreadPool POOL = new ManagedThreadPool(4);

    static {
        // The pool's threads are not daemons and the pool is never shut down, so four idle threads would keep a plain JVM from
        // exiting. A thread that has been idle for a second ends instead (the pool is a ThreadPoolExecutor), and new ones are
        // made on first use again.
        POOL.setKeepAliveTime(1, java.util.concurrent.TimeUnit.SECONDS);
        POOL.allowCoreThreadTimeOut(true);
    }

    private HttpCallbacks() {}
}
