package io.toolgate.approvals.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Sends {@code /toolgate/approvals} to the console's page, on either web stack. */
@RestController
final class ConsoleRedirect {

    static final String PAGE = "/toolgate/approvals/index.html";

    @GetMapping({"/toolgate/approvals", "/toolgate/approvals/"})
    ResponseEntity<Void> console() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(PAGE)).build();
    }
}
