package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 前端分页列表响应，和知识库文档列表保持 records/total/current/size 结构。
 */
public record CardPageVO(
        List<CardListItemVO> records,
        long total,
        long current,
        long size
) {
}
