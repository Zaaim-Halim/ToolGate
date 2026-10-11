package io.toolgate.approvals.web;

import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The REST API on Spring MVC. */
@RestController
@RequestMapping("/toolgate/api/approvals")
final class ApprovalsMvcController {

    private final ApprovalsApi api;

    ApprovalsMvcController(ApprovalsApi api) {
        this.api = api;
    }

    @GetMapping
    List<ApiModel.Item> inbox(Principal user) {
        return api.inbox(user);
    }

    @GetMapping("/history")
    List<ApiModel.Item> history(Principal user, @RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "7") int days, @RequestParam(defaultValue = "100") int limit) {
        return api.history(user, status, days, limit);
    }

    @GetMapping("/item")
    ApiModel.Item item(Principal user, @RequestParam(required = false) String id) {
        return api.item(user, id);
    }

    @GetMapping("/journey")
    ApiModel.JourneyView journey(Principal user, @RequestParam(required = false) String id) {
        return api.journey(user, id);
    }

    @GetMapping("/me")
    ApiModel.Me me(Principal user) {
        return api.me(user);
    }

    @PostMapping("/decisions")
    ApiModel.DecisionResponse decide(Principal user,
                                     @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                     @RequestHeader(value = RequestGuard.HEADER, required = false) String marker,
                                     @RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
                                     @RequestHeader(value = HttpHeaders.HOST, required = false) String host,
                                     HttpServletRequest http,
                                     @RequestBody(required = false) ApiModel.DecisionRequest request) {
        // The Host header, as the browser addressed it, so a proxy that ends TLS does not change the port.
        RequestGuard.check(contentType, marker, origin, host != null ? host : http.getServerName() + ":" + http.getServerPort());
        return api.decide(user, request);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiModel.Problem> refused(ApiException refused) {
        return ResponseEntity.status(refused.status()).body(new ApiModel.Problem(refused.getMessage()));
    }
}
