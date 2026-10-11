package io.toolgate.approvals.web;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** A Spring MVC application whose users sign in with HTTP Basic, for a real browser. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(Fixtures.class)
class BrowserTestApplication {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(basic -> { })
                .build();
    }

    @Bean
    InMemoryUserDetailsManager users() {
        return new InMemoryUserDetailsManager(
                User.withUsername("lena").password("{noop}lena-pass").roles("finance").build(),
                User.withUsername("gus").password("{noop}gus-pass").roles("finance").build(),
                User.withUsername("root").password("{noop}root-pass").roles("toolgate-admin").build());
    }
}
