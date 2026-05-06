package com.yuyu.fishagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.entity.DocumentMetadata;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link DocumentMetadata} 持久化访问。
 */
@Mapper
public interface DocumentMetadataMapper extends BaseMapper<DocumentMetadata> {
}
