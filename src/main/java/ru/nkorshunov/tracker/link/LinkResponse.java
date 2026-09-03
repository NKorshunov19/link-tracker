package ru.nkorshunov.tracker.link;

import java.time.Instant;

public record LinkResponse(
        Long id,
        String subscriber,
        String repository,
        Instant lastPushedAt,
        Instant lastCheckedAt,
        boolean active
) {
    public static LinkResponse from(TrackedLink link) {
        return new LinkResponse(
                link.getId(),
                link.getSubscriber(),
                link.fullName(),
                link.getLastPushedAt(),
                link.getLastCheckedAt(),
                link.isActive());
    }
}
