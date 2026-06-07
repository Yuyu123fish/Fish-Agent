package com.yuyu.fishagent.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.card.dto.CardRelationVO;
import com.yuyu.fishagent.card.dto.ExtractRelationVO;
import com.yuyu.fishagent.card.entity.CardRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 卡片关联 Mapper，阶段 1 主要服务详情页的双向关联展示和删除清理。
 */
@Mapper
public interface CardRelationMapper extends BaseMapper<CardRelation> {

    @Select("""
            SELECT
              cr.id,
              CASE WHEN cr.from_card_id = #{cardId} THEN cr.to_card_id ELSE cr.from_card_id END AS cardId,
              other.title AS cardTitle,
              cr.relation_type AS relationType,
              cr.confidence,
              CASE WHEN cr.from_card_id = #{cardId} THEN 'outgoing' ELSE 'incoming' END AS direction
            FROM card_relation cr
            JOIN knowledge_card other
              ON other.id = CASE WHEN cr.from_card_id = #{cardId} THEN cr.to_card_id ELSE cr.from_card_id END
            WHERE (cr.from_card_id = #{cardId} OR cr.to_card_id = #{cardId})
              AND other.user_id = #{userId}
            ORDER BY cr.created_at DESC, cr.id DESC
            """)
    List<CardRelationVO> selectRelationsForCard(@Param("userId") Long userId, @Param("cardId") Long cardId);

    @Select("""
            SELECT COUNT(*)
            FROM card_relation cr
            JOIN knowledge_card a ON a.id = cr.from_card_id
            JOIN knowledge_card b ON b.id = cr.to_card_id
            WHERE a.user_id = #{userId} AND b.user_id = #{userId}
            """)
    long countForUser(@Param("userId") Long userId);

    @Select("""
            SELECT
              cr.id,
              cr.from_card_id AS fromCardId,
              cr.to_card_id AS toCardId,
              cr.relation_type AS relationType,
              cr.confidence
            FROM card_relation cr
            JOIN knowledge_card a ON a.id = cr.from_card_id
            JOIN knowledge_card b ON b.id = cr.to_card_id
            WHERE a.user_id = #{userId} AND b.user_id = #{userId}
            ORDER BY cr.created_at DESC, cr.id DESC
            """)
    List<ExtractRelationVO> selectAllRelationsForUser(@Param("userId") Long userId);
}
