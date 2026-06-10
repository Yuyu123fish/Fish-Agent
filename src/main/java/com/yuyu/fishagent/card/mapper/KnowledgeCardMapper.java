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
              kc.review_next_at AS reviewNextAt,
              kc.review_count AS reviewCount,
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
            <if test="(groupName == null or groupName == '') and groupId != null">
              AND kc.group_id = #{groupId}
            </if>
            <if test="cardType != null and cardType != ''">
              AND kc.card_type = #{cardType}
            </if>
            <if test="reviewOverdue != null and reviewOverdue == true">
              AND kc.review_next_at IS NOT NULL AND kc.review_next_at &lt;= NOW()
            </if>
            GROUP BY kc.id
            <choose>
              <when test="sortBy == 'createdAt'">ORDER BY kc.created_at <if test="sortOrder == 'ASC'">ASC</if><if test="sortOrder != 'ASC'">DESC</if>, kc.id DESC</when>
              <when test="sortBy == 'updatedAt'">ORDER BY kc.updated_at <if test="sortOrder == 'ASC'">ASC</if><if test="sortOrder != 'ASC'">DESC</if>, kc.id DESC</when>
              <when test="sortBy == 'reviewNextAt'">ORDER BY kc.review_next_at <if test="sortOrder == 'ASC'">ASC</if><if test="sortOrder != 'ASC'">DESC</if>, kc.id DESC</when>
              <otherwise>ORDER BY kc.updated_at DESC, kc.id DESC</otherwise>
            </choose>
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
            @Result(column = "reviewNextAt", property = "reviewNextAt"),
            @Result(column = "reviewCount", property = "reviewCount"),
            @Result(column = "createdAt", property = "createdAt")
    })
    List<CardListItemVO> selectListItems(@Param("userId") Long userId,
                                         @Param("status") String status,
                                         @Param("keyword") String keyword,
                                         @Param("groupName") String groupName,
                                         @Param("groupId") Long groupId,
                                         @Param("cardType") String cardType,
                                         @Param("reviewOverdue") Boolean reviewOverdue,
                                         @Param("sortBy") String sortBy,
                                         @Param("sortOrder") String sortOrder,
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
            <if test="(groupName == null or groupName == '') and groupId != null">
              AND kc.group_id = #{groupId}
            </if>
            <if test="cardType != null and cardType != ''">
              AND kc.card_type = #{cardType}
            </if>
            <if test="reviewOverdue != null and reviewOverdue == true">
              AND kc.review_next_at IS NOT NULL AND kc.review_next_at &lt;= NOW()
            </if>
            </script>
            """)
    long countListItems(@Param("userId") Long userId,
                        @Param("status") String status,
                        @Param("keyword") String keyword,
                        @Param("groupName") String groupName,
                        @Param("groupId") Long groupId,
                        @Param("cardType") String cardType,
                        @Param("reviewOverdue") Boolean reviewOverdue);

    /**
     * 兼容遗留：从 knowledge_card.group_name 聚合，过渡期保留。
     * 统计数据现在从 card_group 表获取，由 CardGroupService.getUserGroupTree 提供。
     */
}
