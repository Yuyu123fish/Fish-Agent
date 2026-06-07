package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 分组树节点：前端渲染树形 Tab / cascader / 面包屑使用。
 */
public record GroupTreeNode(
        Long id,
        String name,
        long cardCount,
        List<GroupTreeNode> children
) {}
