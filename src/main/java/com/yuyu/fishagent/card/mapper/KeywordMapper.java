package com.yuyu.fishagent.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.card.entity.Keyword;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 关键词 Mapper：实体 CRUD 复用 BaseMapper，常用查找写成注解 SQL。
 */
@Mapper
public interface KeywordMapper extends BaseMapper<Keyword> {

    @Select("""
            SELECT *
            FROM keyword
            WHERE user_id = #{userId} AND normalized_name = #{normalizedName}
            LIMIT 1
            """)
    Keyword selectByUserIdAndNormalizedName(@Param("userId") Long userId,
                                            @Param("normalizedName") String normalizedName);

    @Select("""
            SELECT *
            FROM keyword
            WHERE user_id = #{userId}
            ORDER BY card_count DESC, updated_at DESC, id DESC
            """)
    List<Keyword> selectByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT *
            FROM keyword
            WHERE user_id = #{userId} AND category = #{category}
            ORDER BY card_count DESC, name ASC
            """)
    List<Keyword> selectByCategory(@Param("userId") Long userId, @Param("category") String category);

    @Update("""
            UPDATE keyword
            SET card_count = GREATEST(0, card_count + #{delta})
            WHERE id = #{keywordId}
            """)
    void updateCardCount(@Param("keywordId") Long keywordId, @Param("delta") int delta);

    @Select("""
            SELECT k.*
            FROM keyword k
            JOIN card_keyword ck ON ck.keyword_id = k.id
            WHERE ck.card_id = #{cardId}
            ORDER BY k.card_count DESC, k.name ASC
            """)
    List<Keyword> selectKeywordsByCardId(@Param("cardId") Long cardId);
}
