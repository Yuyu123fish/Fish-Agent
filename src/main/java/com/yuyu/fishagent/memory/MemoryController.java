package com.yuyu.fishagent.memory;

import com.yuyu.fishagent.memory.dto.MemoryCompressionRequest;
import com.yuyu.fishagent.memory.dto.MemoryCompressionResult;
import com.yuyu.fishagent.memory.MemoryCompressionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记忆管理接口。
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryCompressionService memoryCompressionService;

    @PostMapping("/compress")
    public MemoryCompressionResult compress(@RequestBody MemoryCompressionRequest request) {
        return memoryCompressionService.compress(request);
    }
}
