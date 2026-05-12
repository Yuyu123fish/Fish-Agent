package com.yuyu.fishagent.auth;

import com.yuyu.fishagent.auth.context.UserContext;
import com.yuyu.fishagent.auth.context.UserContextHolder;
import com.yuyu.fishagent.auth.interceptor.GlobalAuthInterceptor;
import com.yuyu.fishagent.auth.dto.LoginRequest;
import com.yuyu.fishagent.auth.dto.LoginResponse;
import com.yuyu.fishagent.auth.dto.RegisterRequest;
import com.yuyu.fishagent.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 认证相关 REST：注册、登录、登出、当前用户。
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 用户注册。
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        try {
            return ResponseEntity.ok(authService.register(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
        }
    }

    /**
     * 用户登录。
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        try {
            return ResponseEntity.ok(authService.login(req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
        }
    }

    /**
     * 登出：删除服务端会话；需在 Header 携带 token（与白名单一致时可匿名调用）。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        String token = request.getHeader(GlobalAuthInterceptor.HEADER_AUTH_TOKEN);
        authService.logout(token);
        return ResponseEntity.noContent().build();
    }

    /**
     * 当前登录用户信息（需登录；不在白名单）。
     */
    @GetMapping("/me")
    public LoginResponse me() {
        UserContext c = UserContextHolder.get();
        // 理论上拦截器已保证非空；兼容旧会话里 nickname 为空的情况
        String nick = c.nickname();
        if (nick == null || nick.isBlank()) {
            nick = c.username();
        }
        return new LoginResponse(null, c.userId(), nick, c.role());
    }
}
