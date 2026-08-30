package ru.nkorshunov.tracker.link;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.nkorshunov.tracker.common.ApiException;

import java.util.List;

@Service
public class LinkService {

    private final TrackedLinkRepository repository;

    public LinkService(TrackedLinkRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LinkResponse add(AddLinkRequest request) {
        GitHubUrl url = GitHubUrl.parse(request.url())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Ожидается ссылка вида https://github.com/owner/repo"));

        repository.findBySubscriberAndOwnerAndRepository(request.subscriber(), url.owner(), url.repository())
                .ifPresent(existing -> {
                    throw new ApiException(HttpStatus.CONFLICT,
                            "Репозиторий " + url.fullName() + " уже отслеживается");
                });

        return LinkResponse.from(repository.save(new TrackedLink(request.subscriber(), url)));
    }

    @Transactional(readOnly = true)
    public List<LinkResponse> listFor(String subscriber) {
        return repository.findAllBySubscriber(subscriber).stream()
                .map(LinkResponse::from)
                .toList();
    }

    @Transactional
    public void remove(Long id, String subscriber) {
        TrackedLink link = repository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ссылка не найдена"));
        if (!link.getSubscriber().equals(subscriber)) {
            // 404, а не 403: чужая подписка не должна подтверждать своё существование.
            throw new ApiException(HttpStatus.NOT_FOUND, "Ссылка не найдена");
        }
        repository.delete(link);
    }
}
