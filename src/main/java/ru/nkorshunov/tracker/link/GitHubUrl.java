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

    // длины и наборы символов взяты у самого GitHub: владелец до 39 знаков без точек,
    // репозиторий до 100. Свободный [\w.-]+ пропускал бы «..» и уводил путь запроса
    // из /repos наверх, а слишком длинное имя не влезало бы в varchar(128) и падало 500-й
    private static final Pattern PATTERN = Pattern.compile(
            "^https?://(?:www\\.)?github\\.com/([A-Za-z0-9][A-Za-z0-9-]{0,38})/([A-Za-z0-9_.-]{1,100}?)(?:\\.git)?/?$");

    public static Optional<GitHubUrl> parse(String url) {
        if (url == null) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(url.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String repository = matcher.group(2);
        if (repository.equals(".") || repository.equals("..")) {
            return Optional.empty();
        }
        return Optional.of(new GitHubUrl(matcher.group(1), repository));
    }

    public String fullName() {
        return owner + "/" + repository;
    }
}
