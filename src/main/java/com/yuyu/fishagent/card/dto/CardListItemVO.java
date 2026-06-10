package com.yuyu.fishagent.card.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 卡片列表项：只返回列表展示需要的摘要字段，详情内容走单卡接口。
 *
 * <p>这里使用普通 JavaBean 而不是 record，方便 MyBatis 为 keywords JSON 字段挂载 TypeHandler。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CardListItemVO {
    private Long id;
    private String title;
    private String contentPreview;
    private List<String> keywords;
    private String cardType;
    private String sourceType;
    private String status;
    private String groupName;
    private Long groupId;
    private Long relationCount;
    private LocalDateTime reviewNextAt;
    private Integer reviewCount;
    private LocalDateTime createdAt;
}
