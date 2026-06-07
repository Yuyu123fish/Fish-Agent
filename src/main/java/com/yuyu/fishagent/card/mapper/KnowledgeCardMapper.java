package com.yuyu.fishagent.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.card.dto.CardListItemVO;
import com.yuyu.fishagent.card.dto.GroupTreeNode;
import com.yuyu.fishagent.card.entity.KnowledgeCard;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识卡片 Mapper：复杂列表/统计查询写 SQL，简单 CRUD 复用 BaseMapper。
 */
@Mapper
public interface KnowledgeCardMapper extends BaseMapper<KnowledgeCard> {

    @Select("""
            <script>
            SELECT
              kc.id,
              kc.title,
              LEFT(kc.content, 120) AS contentPreview,
              kc.keywords,
              kc.card_type AS cardType,
              kc.source_type AS sourceType,
              kc.status,
              kc.group_name AS groupName,
              kc.group_id AS groupId,
              COUNT(cr.id) AS relationCount,
              kc.created_at AS createdAt
            FROM knowledge_card kc
            LEFT JOIN card_relation cr ON cr.from_card_id = kc.id OR cr.to_card_id = kc.id
            WHERE kc.user_id = #{userId}
            <if test="status != null and status != ''">
              AND kc.status = #{status}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (kc.title LIKE CONCAT('%', #{keyword}, '%') OR kc.content LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="groupName != null and groupName != ''">
              <choose>
                <when test="groupName == '__UNGROUPED__'">
                  AND (kc.group_name IS NULL OR kc.group_name = '') AND kc.group_id IS NULL
                </when>
                <otherwise>
                  AND (kc.group_name = #{groupName} OR kc.group_id = #{groupId})
                </otherwise>
              </choose>
            </if>
            GROUP BY kc.id
            ORDER BY kc.updated_at DESC, kc.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "CardListItemMap", value = {
            @Result(column = "id", property = "id"),
            @Result(column = "title", property = "title"),
            @Result(column = "contentPreview", property = "contentPreview"),
            @Result(column = "keywords", property = "keywords", typeHandler = JacksonTypeHandler.class),
            @Result(column = "cardType", property = "cardType"),
            @Result(column = "sourceType", property = "sourceType"),
            @Result(column = "status", property = "status"),
            @Result(column = "groupName", property = "groupName"),
            @Result(column = "groupId", property = "groupId"),
            @Result(column = "relationCount", property = "relationCount"),
            @Result(column = "createdAt", property = "createdAt")
    })
    List<CardListItemVO> selectListItems(@Param("userId") Long userId,
                                         @Param("status") String status,
                                         @Param("keyword") String keyword,
                                         @Param("groupName") String groupName,
                                         @Param("groupId") Long groupId,
                                         @Param("limit") long limit,
                                         @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM knowledge_card kc
            WHERE kc.user_id = #{userId}
            <if test="status != null and status != ''">
              AND kc.status = #{status}
            </if>
            <if test="keyword != null and keyword != ''">
              AND (kc.title LIKE CONCAT('%', #{keyword}, '%') OR kc.content LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="groupName != null and groupName != ''">
              <choose>
                <when test="groupName == '__UNGROUPED__'">
                  AND (kc.group_name IS NULL OR kc.group_name = '') AND kc.group_id IS NULL
                </when>
                <otherwise>
                  AND (kc.group_name = #{groupName} OR kc.group_id = #{groupId})
                </otherwise>
              </choose>
            </if>
            </script>
            """)
    long countListItems(@Param("userId") Long userId,
                        @Param("status") String status,
                        @Param("keyword") String keyword,
                        @Param("groupName") String groupName,
                        @Param("groupId") Long groupId);

    /**
     * 兼容遗留：从 knowledge_card.group_name 聚合，过渡期保留。
     * 统计数据现在从 card_group 表获取，由 CardGroupService.getUserGroupTree 提供。
     */
}
