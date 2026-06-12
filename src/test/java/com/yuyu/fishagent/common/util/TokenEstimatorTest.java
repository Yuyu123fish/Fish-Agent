package com.yuyu.fishagent.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimatorTest {

    @Test
    void estimatesCjkTextWithHigherDensityThanLatinText() {
        assertThat(TokenEstimator.estimate("你好世界")).isEqualTo(3);
        assertThat(TokenEstimator.estimate("hello world!")).isEqualTo(3);
        assertThat(TokenEstimator.estimate("你好 hello")).isEqualTo(3);
    }

    @Test
    void treatsNullAndBlankAsZeroCost() {
        assertThat(TokenEstimator.estimate(null)).isZero();
        assertThat(TokenEstimator.estimate("")).isZero();
    }
}
