package com.yuyu.fishagent.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.session.RedisSessionManager;
import com.yuyu.fishagent.auth.dto.LoginRequest;
import com.yuyu.fishagent.auth.dto.LoginResponse;
import com.yuyu.fishagent.auth.dto.RegisterRequest;
import com.yuyu.fishagent.auth.entity.SysUser;
import com.yuyu.fishagent.auth.enums.UserRole;
import com.yuyu.fishagent.auth.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 注册、登录、登出等与账号相关的业务逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final RedisSessionManager redisSessionManager;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 新用户注册：用户名唯一，密码 BCrypt 存储。
     *
     * @param req 注册参数
     * @throws IllegalArgumentException 用户名已存在或参数非法
     */
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse register(RegisterRequest req) {
        validateCredential(req.getUsername(), req.getPassword());
        long cnt = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, req.getUsername().trim()));
        if (cnt > 0) {
            throw new IllegalArgumentException("username already exists");
        }
        SysUser u = new SysUser();
        u.setUsername(req.getUsername().trim());
        u.setNickname(req.getNickname() != null && !req.getNickname().isBlank()
                ? req.getNickname().trim()
                : req.getUsername().trim());
        u.setPassword(passwordEncoder.encode(req.getPassword()));
        u.setRole(UserRole.USER.toDbValue());
        LocalDateTime now = LocalDateTime.now();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        sysUserMapper.insert(u);

        return issueSession(u);
    }

    /**
     * 用户名密码校验通过后签发会话。
     *
     * @param req 登录参数
     * @return token 与用户信息
     */
    public LoginResponse login(LoginRequest req) {
        if (req.getUsername() == null || req.getPassword() == null) {
            throw new IllegalArgumentException("username and password required");
        }
        SysUser u = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, req.getUsername().trim()));
        if (u == null || !passwordEncoder.matches(req.getPassword(), u.getPassword())) {
            throw new IllegalArgumentException("invalid username or password");
        }
        return issueSession(u);
    }

    /**
     * 撤销 Redis 会话。
     *
     * @param token 会话 token
     */
    public void logout(String token) {
        redisSessionManager.remove(token);
    }

    /**
     * 根据用户信息签发会话。
     * @param u 用户信息
     * @return token 与用户信息
     */
    private LoginResponse issueSession(SysUser u) {
        String nick = resolveDisplayNickname(u);
        UserContext ctx = new UserContext(u.getId(), u.getUsername(), nick, u.getRole());
        String token = redisSessionManager.createSession(ctx);
        return new LoginResponse(token, u.getId(), nick, u.getRole());
    }

    /** 展示用昵称：库中为空时退回用户名，避免前端拿到 null。 */
    private static String resolveDisplayNickname(SysUser u) {
        String n = u.getNickname();
        if (n != null && !n.isBlank()) {
            return n.trim();
        }
        return u.getUsername();
    }

    private static void validateCredential(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username required");
        }
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("password too short");
        }
    }
}
