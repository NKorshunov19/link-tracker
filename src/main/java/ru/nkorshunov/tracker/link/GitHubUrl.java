package ru.nkorshunov.tracker.link;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор ссылки вида https://github.com/owner/repo на владельца и репозиторий.
 *
 * Регулярное выражение скомпилировано один раз в статическое поле: Pattern.compile
 * внутри метода перекомпилировал бы его на каждый вызов, а это дорого.
 */
public record GitHubUrl(String owner, String repository) {

    private static final Pattern PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?/?$");

    public static Optional<GitHubUrl> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(url.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new GitHubUrl(matcher.group(1), matcher.group(2)));
    }

    public String fullName() {
        return owner + "/" + repository;
    }
}
