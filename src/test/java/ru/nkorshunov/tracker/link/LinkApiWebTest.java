package ru.nkorshunov.tracker.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkApiWebTest {

    private static final String REPO_URL = "https://github.com/spring-projects/spring-boot";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private TrackedLinkRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private void add(String subscriber, String url) throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriber":"%s","url":"%s"}
                                """.formatted(subscriber, url)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("ссылка добавляется и возвращается в списке подписчика")
    void addsAndListsLink() throws Exception {
        add("nikita", REPO_URL);

        mockMvc.perform(get("/api/links").param("subscriber", "nikita"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].repository").value("spring-projects/spring-boot"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    @DisplayName("чужие подписки в списке не видны")
    void listShowsOnlyOwnLinks() throws Exception {
        add("nikita", REPO_URL);
        add("petya", "https://github.com/other/repo");

        mockMvc.perform(get("/api/links").param("subscriber", "nikita"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].subscriber").value("nikita"));
    }

    @Test
    @DisplayName("ссылка не на репозиторий GitHub отклоняется с 400 и ProblemDetail")
    void invalidUrlIsRejected() throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriber":"nikita","url":"https://gitlab.com/owner/repo"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("github.com")));
    }

    @Test
    @DisplayName("ссылка с лишним путём отклоняется, а не сохраняется как репозиторий")
    void urlWithExtraPathIsRejected() throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriber":"nikita","url":"https://github.com/owner/repo/issues/1"}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("пустой подписчик в теле запроса отклоняется")
    void blankSubscriberInBodyIsRejected() throws Exception {
        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriber":"","url":"%s"}
                                """.formatted(REPO_URL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("subscriber")));
    }

    @Test
    @DisplayName("пустой подписчик в параметре запроса — это 400, а не 500")
    void blankSubscriberInQueryIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/links").param("subscriber", " "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("нечисловой идентификатор ссылки даёт 400 без внутренностей в теле")
    void nonNumericIdIsBadRequest() throws Exception {
        String body = mockMvc.perform(delete("/api/links/abc").param("subscriber", "nikita"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("java.", "Exception", "at ru.nkorshunov");
    }

    @Test
    @DisplayName("повторная подписка на тот же репозиторий отклоняется с 409")
    void duplicateSubscriptionIsRejected() throws Exception {
        add("nikita", REPO_URL);

        mockMvc.perform(post("/api/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subscriber":"nikita","url":"%s"}
                                """.formatted(REPO_URL)))
                .andExpect(status().isConflict());

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("тот же репозиторий у другого подписчика — это отдельная подписка")
    void sameRepositoryForAnotherSubscriberIsAllowed() throws Exception {
        add("nikita", REPO_URL);
        add("petya", REPO_URL);

        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("подписка удаляется, повторное удаление даёт 404")
    void removesSubscription() throws Exception {
        add("nikita", REPO_URL);
        Long id = repository.findAll().getFirst().getId();

        mockMvc.perform(delete("/api/links/{id}", id).param("subscriber", "nikita"))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/links/{id}", id).param("subscriber", "nikita"))
                .andExpect(status().isNotFound());

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("чужую подписку удалить нельзя, и ответ не подтверждает её существование")
    void doesNotRemoveSomeoneElsesSubscription() throws Exception {
        add("nikita", REPO_URL);
        Long id = repository.findAll().getFirst().getId();

        mockMvc.perform(delete("/api/links/{id}", id).param("subscriber", "petya"))
                .andExpect(status().isNotFound());

        assertThat(repository.findAll()).hasSize(1);
    }
}
