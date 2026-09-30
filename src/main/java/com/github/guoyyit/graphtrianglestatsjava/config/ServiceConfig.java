package com.github.guoyyit.graphtrianglestatsjava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables graph service configuration properties.
 */
@Configuration
@EnableConfigurationProperties(GraphProperties.class)
public class ServiceConfig {
}
