package com.yuyu.fishagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyu.fishagent.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * {@link SysUser} 持久化访问。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
