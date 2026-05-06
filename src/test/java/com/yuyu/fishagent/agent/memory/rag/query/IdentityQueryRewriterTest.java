package com.yuyu.fishagent.agent.memory.rag.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityQueryRewriterTest {

    private final RagQueryRewrite.IdentityRewriter rewriter = new RagQueryRewrite.IdentityRewriter();

    @Test
    void collapsesWhitespaceAndTrims() {
        assertThat(rewriter.rewrite("  a  \n\t b  ", new RagQueryRewrite.RewriteContext("s1")))
                .isEqualTo("a b");
    }

    @Test
    void emptyAndNull() {
        assertThat(rewriter.rewrite("", new RagQueryRewrite.RewriteContext("s1"))).isEmpty();
        assertThat(rewriter.rewrite(null, new RagQueryRewrite.RewriteContext("s1"))).isEmpty();
    }
}
