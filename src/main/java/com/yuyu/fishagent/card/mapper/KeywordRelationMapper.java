package com.yuyu.fishagent.card.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.card.entity.KeywordRelation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 关键词关系 Mapper。
 */
@Mapper
public interface KeywordRelationMapper extends BaseMapper<KeywordRelation> {

    @Select("""
            SELECT *
            FROM keyword_relation
            WHERE user_id = #{userId}
            """)
    List<KeywordRelation> selectByUserId(@Param("userId") Long userId);
}
