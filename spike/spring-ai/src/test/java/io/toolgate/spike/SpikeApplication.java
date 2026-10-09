package io.toolgate.spike;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

/** A minimal Spring AI application with the gate's manager bean in place of Spring Boot's. */
@SpringBootApplication
class SpikeApplication {

    @Bean
    ToolCallingManager toolCallingManager(ToolCallbackResolver resolver) {
        return new RecordingToolCallingManager(resolver);
    }

    @Bean
    ToolCallbackProvider adminTools() {
        return MethodToolCallbackProvider.builder().toolObjects(new SpikeTools.Admin()).build();
    }

    /** Keeps the body of every MCP tools/call request, to see what the client sends a server. */
    @Bean
    OncePerRequestFilter mcpWireTap() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                var cached = new ContentCachingRequestWrapper(request, 1 << 20);
                chain.doFilter(cached, response);
                String body = new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
                if (request.getRequestURI().startsWith("/mcp") && body.contains("tools/call")) {
                    Recorder.MCP_WIRE.add(body);
                }
            }
        };
    }

    @Bean
    ToolCallbackProvider ticketTools() {
        return MethodToolCallbackProvider.builder().toolObjects(new SpikeTools.Tickets()).build();
    }
}
