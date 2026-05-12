package com.yuyu.fishagent.rag.pipeline.expand;

import com.yuyu.fishagent.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BreakIteratorSubQueryExpanderTest {

    private RagQueryExpand.BreakIteratorExpander expander;

    @BeforeEach
    void setUp() {
        RagProperties rag = new RagProperties();
        rag.getRecall().setMaxSubQueries(20);
        rag.getRecall().setMinTokenChars(1);
        expander = new RagQueryExpand.BreakIteratorExpander(rag);
    }

    @Test
    void includesFullSentenceFirst() {
        List<String> q = expander.expand("我喜欢喝拿铁");
        assertThat(q).isNotEmpty();
        assertThat(q.get(0)).isEqualTo("我喜欢喝拿铁");
    }

    @Test
    void englishSplitsWords() {
        List<String> q = expander.expand("hello world test");
        assertThat(q).contains("hello world test");
        assertThat(q).anyMatch(s -> s.equalsIgnoreCase("hello"));
    }

    @Test
    void respectsMaxSubQueries() {
        RagProperties rag = new RagProperties();
        rag.getRecall().setMaxSubQueries(3);
        rag.getRecall().setMinTokenChars(1);
        RagQueryExpand.BreakIteratorExpander ex = new RagQueryExpand.BreakIteratorExpander(rag);
        List<String> q = ex.expand("一二三四五六七八九十");
        assertThat(q.size()).isLessThanOrEqualTo(3);
    }
}
