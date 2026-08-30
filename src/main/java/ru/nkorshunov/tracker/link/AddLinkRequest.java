package ru.nkorshunov.tracker.link;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddLinkRequest(
        @NotBlank(message = "укажите подписчика")
        @Size(max = 128, message = "не длиннее 128 символов")
        String subscriber,

        @NotBlank(message = "укажите ссылку на репозиторий")
        String url
) {
}
