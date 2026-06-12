package com.yuyu.fishagent.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTruncatorTest {

    @Test
    void truncatePrefersParagraphBoundaryAndAppendsOmissionNotice() {
        String text = "第一段内容完整。\n\n第二段会很长很长很长，超过限制后不应进入结果。";

        String result = TextTruncator.truncate(text, 12);

        assertThat(result).startsWith("第一段内容完整。\n\n");
        assertThat(result).contains("[内容已截断，原始长度");
        assertThat(result).doesNotContain("第二段会很长");
    }

    @Test
    void headTailCompressPreservesBothEnds() {
        String text = "abcdefghijklmnopqrstuvwxyz";

        String result = TextTruncator.headTailCompress(text, 5, 4);

        assertThat(result).startsWith("abcde");
        assertThat(result).contains("[省略 17 字符]");
        assertThat(result).endsWith("wxyz");
    }

    @Test
    void truncateWithinNeverExceedsMaxLength() {
        String text = "第一段内容完整。\n\n第二段内容会非常长，用于测试硬预算截断。";

        String result = TextTruncator.truncateWithin(text, 20);

        assertThat(result).hasSizeLessThanOrEqualTo(20);
    }
}
