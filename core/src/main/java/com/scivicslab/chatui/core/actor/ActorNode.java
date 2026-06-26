package com.scivicslab.chatui.core.actor;

import java.util.List;

/**
 * One node of the actor tree returned by {@code GET /api/actors} for the right-pane Actors tab.
 * The tree is rendered by console.js as {name, type, alive, children[]}.
 */
public record ActorNode(String name, String type, boolean alive, List<ActorNode> children) {}
