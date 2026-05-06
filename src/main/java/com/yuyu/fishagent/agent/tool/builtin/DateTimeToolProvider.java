package com.yuyu.fishagent.agent.tool.builtin;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 时间工具：获取当前日期时间。无任何外部依赖。
 */
@Component
public class DateTimeToolProvider implements AgentToolProvider {

    public record Input(String timezone) {}

    public record Output(String datetime, String timezone) {}

    @Override
    public String name() {
        return "get_current_datetime";
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> {
            String tz = (input == null || input.timezone() == null || input.timezone().isBlank())
                    ? ZoneId.systemDefault().getId()
                    : input.timezone();
            ZoneId zone;
            try {
                zone = ZoneId.of(tz);
            } catch (Exception e) {
                zone = ZoneId.systemDefault();
            }
            String now = LocalDateTime.now(zone)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return new Output(now, zone.getId());
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("获取当前的日期与时间。可选传入 IANA 时区名（如 Asia/Shanghai），不传则使用系统默认时区。")
                .inputType(Input.class)
                .build();
    }
}
