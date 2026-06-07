package com.yuyu.fishagent.card.dto;

import java.util.List;

/**
 * 批量操作请求：仅暴露卡片 ID，用户归属在服务层统一校验。
 */
public record BatchCardIdsRequest(List<Long> ids) {
}
