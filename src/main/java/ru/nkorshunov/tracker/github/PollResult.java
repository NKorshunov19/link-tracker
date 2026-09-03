package ru.nkorshunov.tracker.github;

import java.time.Duration;
import java.time.Instant;

public sealed interface PollResult {

    record Updated(Instant pushedAt, String etag) implements PollResult {
    }

    record NotModified() implements PollResult {
    }

    record NotFound() implements PollResult {
    }

    record RateLimited(Duration retryAfter) implements PollResult {
    }

    record Failed(String reason, boolean retryable) implements PollResult {
    }
}
