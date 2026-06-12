package com.yuyu.fishagent.agent.tool.builtin;

import com.yuyu.fishagent.agent.tool.AgentToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * 网页抓取工具：基于 Jsoup 拉取 URL 并提取正文文本（去脚本/样式）。
 */
@Slf4j
@Component
public class WebFetchToolProvider implements AgentToolProvider {

    public record Input(String url) {}

    public record Output(String url, String title, String text) {}

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public ToolCallback build() {
        Function<Input, Output> fn = input -> {
            if (input == null || input.url() == null || input.url().isBlank()) {
                return new Output("", "", "ERROR: url is required");
            }
            try {
                Document doc = Jsoup.connect(input.url())
                        .userAgent("Mozilla/5.0 (Fish-Agent) Chrome/124")
                        .timeout(15_000)
                        .get();
                doc.select("script, style, noscript, iframe").remove();
                String text = doc.body() == null ? "" : doc.body().text();
                return new Output(input.url(), doc.title(), text);
            } catch (Exception e) {
                log.warn("web_fetch 抓取失败: {} - {}", input.url(), e.getMessage());
                return new Output(input.url(), "", "ERROR: " + e.getMessage());
            }
        };
        return FunctionToolCallback.builder(name(), fn)
                .description("根据 URL 抓取网页正文。返回标题与去除脚本/样式后的纯文本，长结果由工具注册中心统一治理。")
                .inputType(Input.class)
                .build();
    }
}
