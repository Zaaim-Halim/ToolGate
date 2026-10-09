package io.toolgate.email;

import io.toolgate.api.ArgValue;
import io.toolgate.api.ListValue;
import io.toolgate.api.TextValue;
import io.toolgate.spi.ArgumentValidator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** Every recipient of an argument must be at one of the allowed domains. */
final class EmailDomains implements ArgumentValidator {

    private final List<DomainPattern> patterns;

    EmailDomains(List<DomainPattern> patterns) {
        if (patterns.isEmpty()) {
            throw new IllegalArgumentException("emailDomains needs at least one domain");
        }
        this.patterns = List.copyOf(patterns);
    }

    @Override
    public String kind() {
        return "emailDomains";
    }

    @Override
    public String fingerprint() {
        return patterns.stream().map(DomainPattern::toString).sorted().distinct().collect(Collectors.joining(","));
    }

    @Override
    public Optional<String> problem(ArgValue value) {
        return switch (value) {
            case TextValue(String text) -> problem(text);
            case ListValue(List<ArgValue> elements) -> {
                if (elements.isEmpty()) {
                    yield Optional.of("names no recipient");
                }
                if (elements.size() > Recipients.MAX_RECIPIENTS) {
                    yield Optional.of("names more than " + Recipients.MAX_RECIPIENTS + " recipients");
                }
                for (ArgValue element : elements) {
                    Optional<String> problem = element instanceof TextValue(String text)
                            ? problem(text)
                            : Optional.of("must be a list of strings");
                    if (problem.isPresent()) {
                        yield problem;
                    }
                }
                yield Optional.empty();
            }
            default -> Optional.of("must be a string or a list of strings");
        };
    }

    private Optional<String> problem(String text) {
        return switch (Recipients.read(text)) {
            case Recipients.Denied(String reason) -> Optional.of("recipients " + reason);
            case Recipients.Read(List<String> domains) -> domains.stream()
                    .filter(domain -> patterns.stream().noneMatch(pattern -> pattern.matches(domain)))
                    .findFirst()
                    .map(domain -> "recipient domain " + domain + " is not allowed");
        };
    }
}
