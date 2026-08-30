package com.shiv.securegkd.audit.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class AuditServiceConfiguration {

    @Bean
    Clock auditClock() {
        return Clock.systemUTC();
    }
}
