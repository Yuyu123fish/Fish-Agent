package com.yuyu.fishagent.common.resilience;

import com.yuyu.fishagent.agent.ChatAgent;
import com.yuyu.fishagent.rag.pipeline.recall.RagRecall;
import com.yuyu.fishagent.rag.pipeline.rerank.DashScopeRagReranker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerWiringTest {

    @Test
    void chatAgentShouldReceiveCircuitBreakerRegistryForReactiveLlmProtection() {
        assertThat(hasConstructorParameter(ChatAgent.class, CircuitBreakerRegistry.class)).isTrue();
    }

    @Test
    void ragAugmentationShouldReceiveCircuitBreakerHelperForEsProtection() {
        assertThat(hasConstructorParameter(RagRecall.DefaultAugmentation.class, CircuitBreakerHelper.class)).isTrue();
    }

    @Test
    void rerankerShouldReceiveCircuitBreakerHelperForDashScopeProtection() {
        assertThat(hasConstructorParameter(DashScopeRagReranker.class, CircuitBreakerHelper.class)).isTrue();
    }

    private static boolean hasConstructorParameter(Class<?> type, Class<?> parameterType) {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Arrays.asList(constructor.getParameterTypes()).contains(parameterType)) {
                return true;
            }
        }
        return false;
    }
}
