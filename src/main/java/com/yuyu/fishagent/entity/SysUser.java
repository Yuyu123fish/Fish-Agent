package com.yuyu.fishagent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 系统用户实体，映射 {@code sys_user}。
 * <p>密码字段仅在服务端使用，勿序列化到对外 API。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String nickname;

    private String username;

    /** BCrypt 哈希，不落前端 DTO。 */
    private String password;

    private String role;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
