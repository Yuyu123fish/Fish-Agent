package com.yuyu.fishagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆压缩结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemoryCompressionResult {

    private String shortTermSummary;

    private List<String> longTermFacts = new ArrayList<>();
}
