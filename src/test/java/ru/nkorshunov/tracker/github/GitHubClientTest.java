package ru.nkorshunov.tracker.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты клиента против настоящего HTTP-сервера, а не против мока.
 *
 * Разница принципиальная: мок вернул бы то, что я ему велел, и проверил бы только мою
 * же выдумку. WireMock отдаёт реальные коды, заголовки и умеет держать паузу — поэтому
 * здесь проверяются вещи, которые на моках не проверишь: условный запрос с If-None-Match,
 * обработка 304, поведение при таймауте и то, что 404 не повторяется.
 */
class GitHubClientTest {

    private static final String REPO_JSON = """
            {"full_name":"spring-projects/spring-boot","pushed_at":"2026-08-30T10:15:00Z"}
            """;

    private static WireMockServer wireMock;
    private GitHubClient client;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        client = newClient(Duration.ofSeconds(2));
    }

    private GitHubClient newClient(Duration readTimeout) {
        GitHubProperties properties = new GitHubProperties(
                wireMock.baseUrl(), "", 3, Duration.ofMillis(10),
                Duration.ofSeconds(1), readTimeout);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
        return new GitHubClient(restClient, new ObjectMapper(), properties);
    }

    @Test
    @DisplayName("успешный ответ разбирается в Updated с датой и ETag")
    void parsesSuccessfulResponse() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/spring-projects/spring-boot"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", "\"abc123\"")
                        .withBody(REPO_JSON)));

        PollResult result = client.poll("spring-projects", "spring-boot", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.Updated.class, updated -> {
            assertThat(updated.pushedAt()).isEqualTo(Instant.parse("2026-08-30T10:15:00Z"));
            assertThat(updated.etag()).isEqualTo("\"abc123\"");
        });
    }

    @Test
    @DisplayName("при наличии ETag уходит условный запрос, а 304 не считается ошибкой")
    void sendsConditionalRequestAndHandlesNotModified() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/spring-projects/spring-boot"))
                .withHeader(HttpHeaders.IF_NONE_MATCH, equalTo("\"abc123\""))
                .willReturn(aResponse().withStatus(304)));

        PollResult result = client.poll("spring-projects", "spring-boot", "\"abc123\"");

        assertThat(result).isInstanceOf(PollResult.NotModified.class);
        wireMock.verify(getRequestedFor(urlPathEqualTo("/repos/spring-projects/spring-boot"))
                .withHeader(HttpHeaders.IF_NONE_MATCH, equalTo("\"abc123\"")));
    }

    @Test
    @DisplayName("404 не повторяется: удалённый репозиторий не появится обратно")
    void doesNotRetryOnNotFound() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/ghost/repo"))
                .willReturn(aResponse().withStatus(404)));

        PollResult result = client.poll("ghost", "repo", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.Failed.class,
                failed -> assertThat(failed.retryable()).isFalse());
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/ghost/repo")));
    }

    @Test
    @DisplayName("500 повторяется до исчерпания попыток")
    void retriesOnServerError() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .willReturn(aResponse().withStatus(500)));

        PollResult result = client.poll("owner", "repo", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.Failed.class,
                failed -> assertThat(failed.retryable()).isTrue());
        wireMock.verify(3, getRequestedFor(urlPathEqualTo("/repos/owner/repo")));
    }

    @Test
    @DisplayName("после временного сбоя повтор доводит дело до успеха")
    void recoversAfterTransientFailure() {
        String scenario = "временный сбой";
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .inScenario(scenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("восстановился"));
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .inScenario(scenario)
                .whenScenarioStateIs("восстановился")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(REPO_JSON)));

        PollResult result = client.poll("owner", "repo", null);

        assertThat(result).isInstanceOf(PollResult.Updated.class);
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/repos/owner/repo")));
    }

    @Test
    @DisplayName("403 при исчерпании лимита GitHub считается временным сбоем")
    void rateLimitIsRetryable() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .willReturn(aResponse().withStatus(403)));

        PollResult result = client.poll("owner", "repo", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.Failed.class, failed -> {
            assertThat(failed.retryable()).isTrue();
            assertThat(failed.reason()).contains("лимит");
        });
    }

    @Test
    @DisplayName("зависший ответ обрывается по таймауту, а не держит поток вечно")
    void slowResponseIsCutOffByTimeout() {
        GitHubClient impatient = newClient(Duration.ofMillis(200));
        wireMock.stubFor(get(urlPathEqualTo("/repos/slow/repo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(3000)
                        .withBody(REPO_JSON)));

        long startedAt = System.currentTimeMillis();
        PollResult result = impatient.poll("slow", "repo", null);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(result).isInstanceOf(PollResult.Failed.class);
        assertThat(elapsed).as("не дожидались трёх секунд ответа").isLessThan(3000);
    }

    @Test
    @DisplayName("битый JSON не повторяется — он придёт таким же")
    void malformedBodyIsNotRetryable() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{это не json")));

        PollResult result = client.poll("owner", "repo", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.Failed.class,
                failed -> assertThat(failed.retryable()).isFalse());
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/owner/repo")));
    }

    @Test
    @DisplayName("ответ без поля pushed_at не считается обновлением")
    void missingPushedAtIsFailure() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"full_name\":\"owner/repo\"}")));

        assertThat(client.poll("owner", "repo", null))
                .isInstanceOfSatisfying(PollResult.Failed.class,
                        failed -> assertThat(failed.reason()).contains("pushed_at"));
    }
}
