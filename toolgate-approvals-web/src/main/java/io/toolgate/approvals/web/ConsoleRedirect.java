package io.toolgate.approvals.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sends {@code /toolgate/approvals} to the console's page, on either web stack, keeping the {@code id} a
 * notification links to.
 */
@RestController
final class ConsoleRedirect {

    static final String PAGE = "/toolgate/approvals/index.html";

    @GetMapping({"/toolgate/approvals", "/toolgate/approvals/"})
    ResponseEntity<Void> console(@RequestParam(name = "id", required = false) String id) {
        String target = id == null || id.isBlank() ? PAGE : PAGE + "?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
