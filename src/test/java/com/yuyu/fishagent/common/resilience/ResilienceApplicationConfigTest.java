package com.yuyu.fishagent.common.resilience;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceApplicationConfigTest {

    @Test
    void shouldConfigureRequiredCircuitBreakerInstances() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties properties = yaml.getObject();

        assertThat(instanceNames(properties)).contains(
                ResilienceConstants.CB_LLM,
                ResilienceConstants.CB_ES_TEXT,
                ResilienceConstants.CB_ES_VECTOR,
                ResilienceConstants.CB_RERANK
        );
        assertThat(properties).containsEntry(
                "resilience4j.circuitbreaker.instances.llm.slow-call-duration-threshold", "15s");
        assertThat(properties).containsEntry(
                "resilience4j.circuitbreaker.instances.es-text.slow-call-duration-threshold", "3s");
        assertThat(properties).containsEntry(
                "resilience4j.circuitbreaker.instances.es-vector.slow-call-duration-threshold", "5s");
        assertThat(properties).containsEntry(
                "resilience4j.circuitbreaker.instances.rerank.slow-call-duration-threshold", "5s");
    }

    private static Set<String> instanceNames(Properties properties) {
        return properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith("resilience4j.circuitbreaker.instances."))
                .map(name -> name.substring("resilience4j.circuitbreaker.instances.".length()))
                .map(name -> name.substring(0, name.indexOf('.')))
                .collect(java.util.stream.Collectors.toSet());
    }
}
