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
import java.time.Duration;
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

    // ответ /repos весит единицы килобайт; всё, что больше мегабайта, читать в память незачем
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final String RATE_LIMIT_REMAINING = "x-ratelimit-remaining";

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
     * репозиторий не появится обратно, а лимит мы потратим. Исчерпанный лимит
     * тоже не повторяем — он восстановится минутами, а не секундами backoff.
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
                    // exchange без второго аргумента закрывает ответ сам: с close=false
                    // соединение остаётся висеть, и планировщик за сутки исчерпает сокеты
                    .exchange((request, response) -> handle(response));
        } catch (Exception e) {
            // Сюда попадают таймауты и обрывы соединения — это временные сбои, их повторяем.
            log.warn("Сбой обращения к GitHub для {}/{}: {}", owner, repository, e.toString());
            return new PollResult.Failed("сбой соединения: " + e.getMessage(), true);
        }
    }

    private PollResult handle(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response)
            throws IOException {
        HttpStatusCode status = response.getStatusCode();
        HttpHeaders headers = response.getHeaders();

        if (status.value() == 304) {
            return new PollResult.NotModified();
        }
        if (status.is2xxSuccessful()) {
            // читаем на байт больше лимита: если он пришёл, тело слишком большое
            byte[] body = response.getBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                return new PollResult.Failed("ответ длиннее " + MAX_BODY_BYTES + " байт", false);
            }
            return parse(new String(body, StandardCharsets.UTF_8), headers.getETag());
        }
        if (status.value() == 404) {
            return new PollResult.NotFound();
        }
        if (status.value() == 429 || isRateLimited(status, headers)) {
            return new PollResult.RateLimited(retryAfter(headers));
        }
        if (status.value() == 401 || status.value() == 403) {
            // не лимит, а именно нет доступа: приватный или заблокированный репозиторий
            return new PollResult.Failed("нет доступа, GitHub ответил " + status.value(), false);
        }
        if (status.is5xxServerError()) {
            return new PollResult.Failed("GitHub ответил " + status.value(), true);
        }
        return new PollResult.Failed("неожиданный ответ " + status.value(), false);
    }

    /** 403 у GitHub означает и «нет доступа», и «кончился лимит»; различает их только заголовок. */
    private boolean isRateLimited(HttpStatusCode status, HttpHeaders headers) {
        return status.value() == 403 && "0".equals(headers.getFirst(RATE_LIMIT_REMAINING));
    }

    private Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            // GitHub присылает секунды; формат с HTTP-датой здесь не разбираем
            return Duration.ofSeconds(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
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
