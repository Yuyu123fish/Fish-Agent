package com.yuyu.fishagent.common.trace;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * dev profile 下的 trace 查询端点。
 */
@Profile("dev")
@RestController
@RequestMapping("/admin/trace")
@RequiredArgsConstructor
public class TraceQueryController {

    private final ObjectProvider<ElasticsearchOperations> operationsProvider;
    private final TraceProperties properties;

    @GetMapping("/{turnId}")
    public TurnTrace get(@PathVariable String turnId) {
        ElasticsearchOperations operations = operationsProvider.getIfAvailable();
        if (operations == null || turnId == null || turnId.isBlank()) {
            return null;
        }
        return operations.get(turnId, TurnTrace.class, IndexCoordinates.of(properties.getEsIndex()));
    }
}
