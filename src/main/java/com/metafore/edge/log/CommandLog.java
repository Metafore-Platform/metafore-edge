package com.metafore.edge.log;

import org.slf4j.Logger;

/** The one line a route writes when a command arrives: what it was, and the command with its secrets masked. */
public final class CommandLog {

    private CommandLog() {
    }

    public static void received(Logger log, String what, String body) {
        log.info("{}: {}", what, Redactor.json(body));
    }
}
