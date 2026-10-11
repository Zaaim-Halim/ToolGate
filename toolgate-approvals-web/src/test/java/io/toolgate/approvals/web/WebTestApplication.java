package io.toolgate.approvals.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * A Spring MVC application with a careless security setup: it lets anyone reach {@code /toolgate/**}
 * and turned CSRF protection off. The console must stay safe anyway.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(Fixtures.class)
class WebTestApplication {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(basic -> { })
                .build();
    }
}
