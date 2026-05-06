package com.yuyu.fishagent.agent.tool.external;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 邮件发送工具，基于 {@link JavaMailSender} 自动装配。
 * <p>仅当 {@code spring.mail.host} 配置时启用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "spring.mail", name = "host")
public class MailToolProvider implements AgentToolProvider {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String defaultFrom;

    public record Input(String to, String subject, String content) {}

    public record Output(String to, String subject, String status) {}

    @Override
    public String name() {
        return "send_mail";
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> {
            if (input == null || input.to() == null || input.to().isBlank()) {
                return new Output("", "", "ERROR: to is required");
            }
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                if (defaultFrom != null && !defaultFrom.isBlank()) {
                    msg.setFrom(defaultFrom);
                }
                msg.setTo(input.to().split("[,;]"));
                msg.setSubject(input.subject() == null ? "(no subject)" : input.subject());
                msg.setText(input.content() == null ? "" : input.content());
                mailSender.send(msg);
                return new Output(input.to(), input.subject(), "SENT");
            } catch (Exception e) {
                log.warn("发送邮件失败: {}", e.getMessage());
                return new Output(input.to(), input.subject(), "ERROR: " + e.getMessage());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("发送纯文本邮件。to 接收者邮箱（多个用逗号或分号分隔），subject 主题，content 正文。")
                .inputType(Input.class)
                .build();
    }
}
