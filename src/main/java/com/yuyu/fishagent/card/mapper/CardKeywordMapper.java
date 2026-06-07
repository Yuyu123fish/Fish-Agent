package com.yuyu.fishagent.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.card.entity.CardKeyword;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 卡片-关键词关联 Mapper。
 */
@Mapper
public interface CardKeywordMapper extends BaseMapper<CardKeyword> {

    @Delete("DELETE FROM card_keyword WHERE card_id = #{cardId}")
    void deleteByCardId(@Param("cardId") Long cardId);

    @Select("""
            <script>
            SELECT DISTINCT card_id
            FROM card_keyword
            WHERE keyword_id IN
            <foreach collection="keywordIds" item="id" open="(" close=")" separator=",">
              #{id}
            </foreach>
            </script>
            """)
    List<Long> selectCardIdsByKeywordIds(@Param("keywordIds") List<Long> keywordIds);

    @Select("""
            SELECT ck.*
            FROM card_keyword ck
            JOIN keyword k ON k.id = ck.keyword_id
            WHERE k.user_id = #{userId}
            """)
    List<CardKeyword> selectByUserId(@Param("userId") Long userId);
}
