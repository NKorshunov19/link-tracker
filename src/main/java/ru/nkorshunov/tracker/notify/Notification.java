package ru.nkorshunov.tracker.notify;

import java.time.Instant;

public record Notification(String subscriber, String repositoryFullName, Instant pushedAt) {

    public String text() {
        return "Репозиторий %s обновлён: %s".formatted(repositoryFullName, pushedAt);
    }
}
