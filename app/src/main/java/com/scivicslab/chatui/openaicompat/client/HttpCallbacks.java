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

    private HttpCallbacks() {}
}
