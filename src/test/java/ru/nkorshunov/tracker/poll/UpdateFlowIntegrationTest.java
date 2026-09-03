package ru.nkorshunov.tracker.poll;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.nkorshunov.tracker.link.AddLinkRequest;
import ru.nkorshunov.tracker.link.LinkService;
import ru.nkorshunov.tracker.link.TrackedLink;
import ru.nkorshunov.tracker.link.TrackedLinkRepository;
import ru.nkorshunov.tracker.notify.LoggingNotifier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сквозной сценарий: добавили ссылку → планировщик опросил GitHub → пришло уведомление.
 *
 * GitHub подменён WireMock-сервером, его адрес подставляется в конфигурацию через
 * @DynamicPropertySource — порт выделяется операционной системой, поэтому тесты
 * не конфликтуют друг с другом и с занятыми портами на машине.
 */
@SpringBootTest
@ActiveProfiles("test")
class UpdateFlowIntegrationTest {

    private static final String REPO_PATH = "/repos/spring-projects/spring-boot";
    private static final String REPO_URL = "https://github.com/spring-projects/spring-boot";

    private static WireMockServer wireMock;

    @Autowired
    private LinkService linkService;
    @Autowired
    private UpdateChecker updateChecker;
    @Autowired
    private TrackedLinkRepository repository;
    @Autowired
    private LoggingNotifier notifier;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void gitHubUrl(DynamicPropertyRegistry registry) {
        registry.add("app.github.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        repository.deleteAll();
        notifier.clear();
    }

    private void stubPushedAt(String pushedAt, String etag) {
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("ETag", etag)
                        .withBody("""
                                {"full_name":"spring-projects/spring-boot","pushed_at":"%s"}
                                """.formatted(pushedAt))));
    }

    @Test
    @DisplayName("первая проверка запоминает состояние и молчит, вторая уведомляет об обновлении")
    void notifiesOnlyOnActualUpdate() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));

        stubPushedAt("2026-08-30T10:00:00Z", "\"v1\"");
        updateChecker.runOnce();
        assertThat(notifier.recent()).as("на первой проверке уведомления быть не должно").isEmpty();

        stubPushedAt("2026-08-31T12:00:00Z", "\"v2\"");
        updateChecker.runOnce();

        assertThat(notifier.recent()).hasSize(1);
        assertThat(notifier.recent().getFirst().repositoryFullName()).isEqualTo("spring-projects/spring-boot");
        assertThat(notifier.recent().getFirst().subscriber()).isEqualTo("nikita");
    }

    @Test
    @DisplayName("повторная проверка без изменений не шлёт уведомление второй раз")
    void doesNotNotifyTwiceForSamePush() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        stubPushedAt("2026-08-30T10:00:00Z", "\"v1\"");
        updateChecker.runOnce();

        stubPushedAt("2026-08-31T12:00:00Z", "\"v2\"");
        updateChecker.runOnce();
        updateChecker.runOnce();
        updateChecker.runOnce();

        assertThat(notifier.recent()).as("уведомление ровно одно").hasSize(1);
    }

    @Test
    @DisplayName("после первой проверки в запрос уходит ETag")
    void storesAndReusesEtag() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        stubPushedAt("2026-08-30T10:00:00Z", "\"v1\"");

        updateChecker.runOnce();

        TrackedLink stored = repository.findAll().getFirst();
        assertThat(stored.getEtag()).isEqualTo("\"v1\"");
        assertThat(stored.getLastCheckedAt()).isNotNull();
    }

    @Test
    @DisplayName("ответ 304 обновляет время проверки, но не трогает состояние репозитория")
    void notModifiedKeepsState() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        stubPushedAt("2026-08-30T10:00:00Z", "\"v1\"");
        updateChecker.runOnce();

        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH)).willReturn(aResponse().withStatus(304)));
        updateChecker.runOnce();

        TrackedLink stored = repository.findAll().getFirst();
        assertThat(stored.getLastPushedAt()).isEqualTo(java.time.Instant.parse("2026-08-30T10:00:00Z"));
        assertThat(stored.getConsecutiveFailures()).isZero();
        assertThat(notifier.recent()).isEmpty();
    }

    @Test
    @DisplayName("удалённый репозиторий (404) отключается сразу, без ожидания лимита неудач")
    void missingRepositoryIsDeactivatedImmediately() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH)).willReturn(aResponse().withStatus(404)));

        updateChecker.runOnce();

        assertThat(repository.findAll().getFirst().isActive()).isFalse();
    }

    @Test
    @DisplayName("после лимита подряд идущих временных сбоев ссылка отключается")
    void repeatedServerErrorsDeactivateLink() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH)).willReturn(aResponse().withStatus(500)));

        // failure-limit в тестовой конфигурации равен 3
        updateChecker.runOnce();
        assertThat(repository.findAll().getFirst().isActive()).isTrue();
        updateChecker.runOnce();
        updateChecker.runOnce();

        TrackedLink stored = repository.findAll().getFirst();
        assertThat(stored.getConsecutiveFailures()).isEqualTo(3);
        assertThat(stored.isActive()).isFalse();
    }

    @Test
    @DisplayName("отключённая ссылка больше не опрашивается")
    void inactiveLinkIsSkipped() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH)).willReturn(aResponse().withStatus(404)));
        updateChecker.runOnce();
        wireMock.resetAll();

        int processed = updateChecker.runOnce();

        assertThat(processed).isZero();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("исчерпанный лимит GitHub не выключает ссылку и не копит неудачи")
    void rateLimitDoesNotDeactivateLink() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("x-ratelimit-remaining", "0")));

        for (int i = 0; i < 5; i++) {
            updateChecker.runOnce();
        }

        TrackedLink stored = repository.findAll().getFirst();
        assertThat(stored.isActive()).isTrue();
        assertThat(stored.getConsecutiveFailures()).isZero();
        assertThat(stored.getLastCheckedAt()).as("время проверки всё равно сдвигается").isNotNull();
    }

    @Test
    @DisplayName("один битый ответ не выключает ссылку сразу, а идёт в счётчик неудач")
    void malformedResponseCountsAsFailure() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{это не json")));

        updateChecker.runOnce();

        TrackedLink stored = repository.findAll().getFirst();
        assertThat(stored.isActive()).isTrue();
        assertThat(stored.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("сбой проверки не стирает ETag: следующий запрос снова условный")
    void failedCheckKeepsEtag() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        stubPushedAt("2026-08-30T10:00:00Z", "\"v1\"");
        updateChecker.runOnce();

        wireMock.resetAll();
        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH)).willReturn(aResponse().withStatus(500)));
        updateChecker.runOnce();

        assertThat(repository.findAll().getFirst().getEtag()).isEqualTo("\"v1\"");
    }

    @Test
    @DisplayName("сбой на одной ссылке не мешает обработать остальные")
    void failureOnOneLinkDoesNotStopOthers() {
        linkService.add(new AddLinkRequest("nikita", REPO_URL));
        linkService.add(new AddLinkRequest("nikita", "https://github.com/other/repo"));

        wireMock.stubFor(get(urlPathEqualTo(REPO_PATH)).willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(get(urlPathEqualTo("/repos/other/repo"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"full_name":"other/repo","pushed_at":"2026-08-31T09:00:00Z"}
                                """)));

        int processed = updateChecker.runOnce();

        assertThat(processed).isEqualTo(2);
        TrackedLink healthy = repository.findAll().stream()
                .filter(link -> link.getRepository().equals("repo"))
                .findFirst()
                .orElseThrow();
        assertThat(healthy.getLastPushedAt()).isNotNull();
        assertThat(healthy.getConsecutiveFailures()).isZero();
    }
}
