package ru.nkorshunov.tracker.link;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/links")
@Validated
public class LinkController {

    private final LinkService linkService;

    public LinkController(LinkService linkService) {
        this.linkService = linkService;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> add(@Valid @RequestBody AddLinkRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(linkService.add(request));
    }

    @GetMapping
    public List<LinkResponse> list(@RequestParam @NotBlank String subscriber) {
        return linkService.listFor(subscriber);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable Long id, @RequestParam @NotBlank String subscriber) {
        linkService.remove(id, subscriber);
        return ResponseEntity.noContent().build();
    }
}
