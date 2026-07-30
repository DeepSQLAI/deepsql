package com.dbaagent.service;

import java.util.function.Supplier;

public final class QueryActorContextHolder {

    private static final ThreadLocal<String> ACTOR_USERNAME = new ThreadLocal<>();

    private QueryActorContextHolder() {
    }

    public static String currentUsername() {
        return ACTOR_USERNAME.get();
    }

    public static <T> T withActor(String username, Supplier<T> supplier) {
        String previous = ACTOR_USERNAME.get();
        try {
            if (username == null || username.isBlank()) {
                ACTOR_USERNAME.remove();
            } else {
                ACTOR_USERNAME.set(username);
            }
            return supplier.get();
        } finally {
            if (previous == null || previous.isBlank()) {
                ACTOR_USERNAME.remove();
            } else {
                ACTOR_USERNAME.set(previous);
            }
        }
    }

    public static void withActor(String username, Runnable runnable) {
        withActor(username, () -> {
            runnable.run();
            return null;
        });
    }
}
