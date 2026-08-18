package com.shiv.securegkd.security;

import com.shiv.securegkd.authentication.AuthenticationRole;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@TestConfiguration(proxyBeanMethods = false)
public class AllocationTestSecurityConfiguration {

    @Bean
    @Order(0)
    SecurityFilterChain allocationIntegrationTestFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/games/*/allocations")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasRole("USER"))
                .anonymous(anonymous -> anonymous
                        .principal("allocation-runtime-test")
                        .authorities(AuthenticationRole.USER.authority())
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }
}
