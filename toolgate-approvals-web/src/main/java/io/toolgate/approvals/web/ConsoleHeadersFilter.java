package io.toolgate.approvals.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Adds the console's headers to every response under {@code /toolgate/}, on Spring MVC. */
final class ConsoleHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse http) {
            ConsoleHeaders.HEADERS.forEach(http::setHeader);
        }
        chain.doFilter(request, response);
    }
}
