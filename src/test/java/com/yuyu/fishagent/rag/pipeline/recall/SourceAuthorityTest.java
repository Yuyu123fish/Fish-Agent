package com.yuyu.fishagent.rag.pipeline.recall;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAuthorityTest {

    @Test
    void labelsOfficialWhenAuthorityReachesOfficialThreshold() {
        assertThat(SourceAuthority.labelForKnowledge(1.0, false)).isEqualTo("官方");
        assertThat(SourceAuthority.labelForKnowledge(0.7, false)).isEqualTo("用户");
        assertThat(SourceAuthority.labelForKnowledge(0.5, true)).isEqualTo("公开");
    }
}
