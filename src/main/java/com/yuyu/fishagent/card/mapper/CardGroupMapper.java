package com.yuyu.fishagent.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.card.entity.CardGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 卡片分组 Mapper：实体 CRUD 复用 BaseMapper，常用查找写成注解 SQL。
 */
@Mapper
public interface CardGroupMapper extends BaseMapper<CardGroup> {

    @Select("""
            SELECT *
            FROM card_group
            WHERE user_id = #{userId} AND normalized_name = #{normalizedName}
            LIMIT 1
            """)
    CardGroup selectByUserIdAndNormalizedName(@Param("userId") Long userId,
                                              @Param("normalizedName") String normalizedName);

    @Select("""
            SELECT *
            FROM card_group
            WHERE user_id = #{userId}
            ORDER BY card_count DESC, updated_at DESC, id DESC
            """)
    List<CardGroup> selectByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE card_group
            SET card_count = GREATEST(0, card_count + #{delta})
            WHERE id = #{groupId}
            """)
    void updateCardCount(@Param("groupId") Long groupId, @Param("delta") int delta);

    @Select("""
            SELECT *
            FROM card_group
            WHERE id = #{groupId}
            """)
    CardGroup selectById(@Param("groupId") Long groupId);
}
