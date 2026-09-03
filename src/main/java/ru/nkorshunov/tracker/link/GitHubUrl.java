package ru.nkorshunov.tracker.link;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record GitHubUrl(String owner, String repository) {

    // длины и набор символов взяты у GitHub: свободный [\w.-]+ пропускал «..» в путь запроса
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
