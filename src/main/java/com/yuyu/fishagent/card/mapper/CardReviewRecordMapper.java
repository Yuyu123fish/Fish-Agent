package com.yuyu.fishagent.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.card.entity.CardReviewRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 复习记录 Mapper：保留最新记录、日历统计等聚合查询。
 */
@Mapper
public interface CardReviewRecordMapper extends BaseMapper<CardReviewRecord> {

    @Select("""
            SELECT *
            FROM card_review_record
            WHERE card_id = #{cardId} AND user_id = #{userId}
            ORDER BY reviewed_at DESC, id DESC
            LIMIT 1
            """)
    CardReviewRecord selectLatestByCardAndUser(@Param("cardId") Long cardId, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(DISTINCT DATE(reviewed_at))
            FROM card_review_record
            WHERE user_id = #{userId} AND reviewed_at >= #{since}
            """)
    int countDistinctReviewDays(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    @Select("""
            SELECT DATE(reviewed_at) AS d, COUNT(*) AS c
            FROM card_review_record
            WHERE user_id = #{userId} AND reviewed_at >= #{since}
            GROUP BY DATE(reviewed_at)
            ORDER BY d
            """)
    List<Map<String, Object>> selectDailyCounts(@Param("userId") Long userId, @Param("since") LocalDateTime since);
}
