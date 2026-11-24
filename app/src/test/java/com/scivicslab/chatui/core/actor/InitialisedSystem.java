package com.scivicslab.chatui.core.actor;

/** An actor system started without a log, for tests outside this package that need conversations to exist. */
public final class InitialisedSystem {

    private InitialisedSystem() {
    }

    /** @return a newly initialised system, which has {@code project1/chat-01} */
    public static ChatUiActorSystem start() {
        ChatUiActorSystem system = new ChatUiActorSystem();
        system.init();
        return system;
    }
}
