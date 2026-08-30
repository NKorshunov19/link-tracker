package ru.nkorshunov.tracker.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubUrlTest {

    @ParameterizedTest(name = "{0} разбирается")
    @ValueSource(strings = {
            "https://github.com/spring-projects/spring-boot",
            "http://github.com/spring-projects/spring-boot",
            "https://www.github.com/spring-projects/spring-boot",
            "https://github.com/spring-projects/spring-boot/",
            "https://github.com/spring-projects/spring-boot.git",
            "  https://github.com/spring-projects/spring-boot  "
    })
    @DisplayName("принимает разумные варианты записи ссылки")
    void parsesCommonForms(String url) {
        assertThat(GitHubUrl.parse(url))
                .contains(new GitHubUrl("spring-projects", "spring-boot"));
    }

    @ParameterizedTest(name = "\"{0}\" отклоняется")
    @ValueSource(strings = {
            "https://gitlab.com/owner/repo",
            "https://github.com/owner",
            "https://github.com/owner/repo/issues/1",
            "github.com/owner/repo",
            "не ссылка",
            ""
    })
    @DisplayName("отклоняет то, что не является ссылкой на репозиторий")
    void rejectsWrongUrls(String url) {
        assertThat(GitHubUrl.parse(url)).isEmpty();
    }

    @Test
    @DisplayName("null не роняет разбор")
    void nullIsHandled() {
        assertThat(GitHubUrl.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("имена с точками и дефисами разбираются")
    void handlesDotsAndDashes() {
        assertThat(GitHubUrl.parse("https://github.com/my-org/my.repo-name"))
                .contains(new GitHubUrl("my-org", "my.repo-name"));
    }
}
