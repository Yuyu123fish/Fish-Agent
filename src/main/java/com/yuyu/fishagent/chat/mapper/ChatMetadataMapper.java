package com.yuyu.fishagent.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.chat.entity.ChatMetadata;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link ChatMetadata} 持久化访问。
 */
@Mapper
public interface ChatMetadataMapper extends BaseMapper<ChatMetadata> {
}
