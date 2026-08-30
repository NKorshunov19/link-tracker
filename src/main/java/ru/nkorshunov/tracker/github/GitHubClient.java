package ru.nkorshunov.tracker.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Клиент к публичному API GitHub.
 *
 * Взят RestClient, а не WebClient: задача синхронная — планировщик по очереди опрашивает
 * репозитории и ждёт ответа. WebClient потянул бы за собой весь реактивный стек ради
 * блокирующего по сути кода. RestClient даёт тот же удобный API на обычном сервлете.
 */
@Component
public class GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final BackoffRetry retry;

    public GitHubClient(RestClient gitHubRestClient, ObjectMapper objectMapper, GitHubProperties properties) {
        this.restClient = gitHubRestClient;
        this.objectMapper = objectMapper;
        this.retry = new BackoffRetry(properties.maxAttempts(), properties.initialBackoff());
    }

    /**
     * Спрашивает, менялся ли репозиторий.
     *
     * Если передан etag прошлого ответа, уходит условный запрос с If-None-Match.
     * GitHub на неизменившийся репозиторий отвечает 304 — такой ответ не тратит
     * лимит запросов и не тянет тело. На 5000 запросов в час это принципиально.
     *
     * Повторы делаются только для временных сбоев. Повторять 404 бессмысленно:
     * репозиторий не появится обратно, а лимит мы потратим.
     */
    public PollResult poll(String owner, String repository, String etag) {
        return retry.execute(
                () -> singleAttempt(owner, repository, etag),
                result -> result instanceof PollResult.Failed failed && failed.retryable());
    }

    private PollResult singleAttempt(String owner, String repository, String etag) {
        try {
            return restClient.get()
                    .uri("/repos/{owner}/{repo}", owner, repository)
                    .accept(MediaType.valueOf("application/vnd.github+json"))
                    .headers(headers -> {
                        if (etag != null && !etag.isBlank()) {
                            headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                        }
                    })
                    .exchange((request, response) -> handle(response), false);
        } catch (Exception e) {
            // Сюда попадают таймауты и обрывы соединения — это временные сбои, их повторяем.
            log.warn("Сбой обращения к GitHub для {}/{}: {}", owner, repository, e.toString());
            return new PollResult.Failed("сбой соединения: " + e.getMessage(), true);
        }
    }

    private PollResult handle(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();

        if (status.value() == 304) {
            return new PollResult.NotModified();
        }
        if (status.is2xxSuccessful()) {
            String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
            return parse(body, response.getHeaders().getETag());
        }
        if (status.value() == 404) {
            return new PollResult.Failed("репозиторий не найден", false);
        }
        if (status.value() == 403 || status.value() == 429) {
            // GitHub отдаёт 403 при исчерпании лимита. Это временно — лимит восстановится.
            return new PollResult.Failed("превышен лимит запросов GitHub", true);
        }
        if (status.is5xxServerError()) {
            return new PollResult.Failed("GitHub ответил " + status.value(), true);
        }
        return new PollResult.Failed("неожиданный ответ " + status.value(), false);
    }

    private PollResult parse(String body, String etag) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode pushedAt = root.get("pushed_at");
            if (pushedAt == null || pushedAt.isNull()) {
                return new PollResult.Failed("в ответе нет поля pushed_at", false);
            }
            return new PollResult.Updated(Instant.parse(pushedAt.asText()), etag);
        } catch (IOException | DateTimeParseException e) {
            // Битый ответ повторять смысла нет — он придёт таким же.
            return new PollResult.Failed("не удалось разобрать ответ: " + e.getMessage(), false);
        }
    }
}
