package ru.nkorshunov.tracker.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

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
    @DisplayName("с ETag уходит условный запрос, 304 не ошибка")
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
    @DisplayName("404 не повторяется")
    void doesNotRetryOnNotFound() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/ghost/repo"))
                .willReturn(aResponse().withStatus(404)));

        PollResult result = client.poll("ghost", "repo", null);

        assertThat(result).isInstanceOf(PollResult.NotFound.class);
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
    @DisplayName("403 с исчерпанным лимитом — RateLimited без повторов")
    void exhaustedRateLimitIsNotRetriedInPlace() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("x-ratelimit-remaining", "0")));

        PollResult result = client.poll("owner", "repo", null);

        assertThat(result).isInstanceOf(PollResult.RateLimited.class);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/owner/repo")));
    }

    @Test
    @DisplayName("429 отдаёт RateLimited и сохраняет Retry-After")
    void tooManyRequestsKeepsRetryAfter() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withHeader("Retry-After", "60")));

        PollResult result = client.poll("owner", "repo", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.RateLimited.class,
                limited -> assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(60)));
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/owner/repo")));
    }

    @Test
    @DisplayName("403 без заголовков лимита — это отказ в доступе, а не лимит")
    void forbiddenWithoutRateLimitHeaderIsNotRateLimited() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/private"))
                .willReturn(aResponse().withStatus(403)));

        PollResult result = client.poll("owner", "private", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.Failed.class, failed -> {
            assertThat(failed.retryable()).isFalse();
            assertThat(failed.reason()).contains("нет доступа");
        });
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/repos/owner/private")));
    }

    @Test
    @DisplayName("слишком большое тело не читается целиком")
    void oversizedBodyIsRejected() {
        String padding = "x".repeat(2 * 1024 * 1024);
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/huge"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"pushed_at\":\"2026-08-30T10:15:00Z\",\"junk\":\"" + padding + "\"}")));

        PollResult result = client.poll("owner", "huge", null);

        assertThat(result).isInstanceOfSatisfying(PollResult.Failed.class,
                failed -> assertThat(failed.reason()).contains("длиннее"));
    }

    @Test
    @DisplayName("зависший ответ обрывается по таймауту")
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
    @DisplayName("битый JSON не повторяется")
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
    @DisplayName("ответ закрывается после разбора")
    void closesResponseAfterExchange() {
        wireMock.stubFor(get(urlPathEqualTo("/repos/owner/repo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(REPO_JSON)));

        CountingRequestFactory factory = new CountingRequestFactory(new SimpleClientHttpRequestFactory());
        GitHubProperties properties = new GitHubProperties(
                wireMock.baseUrl(), "", 1, Duration.ofMillis(10),
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        GitHubClient tracked = new GitHubClient(
                RestClient.builder().baseUrl(wireMock.baseUrl()).requestFactory(factory).build(),
                new ObjectMapper(), properties);

        tracked.poll("owner", "repo", null);

        assertThat(factory.closes.get()).isEqualTo(1);
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

    private record CountingRequestFactory(ClientHttpRequestFactory delegate, AtomicInteger closes)
            implements ClientHttpRequestFactory {
        CountingRequestFactory(ClientHttpRequestFactory delegate) {
            this(delegate, new AtomicInteger());
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            ClientHttpRequest request = delegate.createRequest(uri, httpMethod);
            return new ClientHttpRequest() {
                @Override
                public ClientHttpResponse execute() throws IOException {
                    return new CountingResponse(request.execute(), closes);
                }

                @Override
                public OutputStream getBody() throws IOException {
                    return request.getBody();
                }

                @Override
                public HttpMethod getMethod() {
                    return request.getMethod();
                }

                @Override
                public URI getURI() {
                    return request.getURI();
                }

                @Override
                public HttpHeaders getHeaders() {
                    return request.getHeaders();
                }

                @Override
                public Map<String, Object> getAttributes() {
                    return request.getAttributes();
                }
            };
        }
    }

    private record CountingResponse(ClientHttpResponse delegate, AtomicInteger closes)
            implements ClientHttpResponse {
        @Override
        public void close() {
            closes.incrementAndGet();
            delegate.close();
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public InputStream getBody() throws IOException {
            return delegate.getBody();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }
}
