package com.motion.backend.processing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationTimestampTest {

    @Test
    void parsesGoSupportedTimestampForms() {
        assertThat(OperationTimestamp.parse("12")).isEqualTo(12D);
        assertThat(OperationTimestamp.parse(" 12.5 ")).isEqualTo(12.5D);
        assertThat(OperationTimestamp.parse("01:02")).isEqualTo(62D);
        assertThat(OperationTimestamp.parse("01:02:03.5")).isEqualTo(3723.5D);
        assertThat(OperationTimestamp.parseOptional(null)).isZero();
        assertThat(OperationTimestamp.parseOptional("")).isZero();
    }

    @Test
    void rejectsInvalidOperationTimestampForms() {
        assertThatThrownBy(() -> OperationTimestamp.parse("")).isInstanceOf(ProcessingException.class)
                .hasMessage("timestamp is empty");
        assertThatThrownBy(() -> OperationTimestamp.parse("1:2:3:4"))
                .isInstanceOf(ProcessingException.class).hasMessage("invalid timestamp");
        assertThatThrownBy(() -> OperationTimestamp.parse("00:60"))
                .isInstanceOf(ProcessingException.class).hasMessage("invalid timestamp");
        assertThatThrownBy(() -> OperationTimestamp.parse("-1"))
                .isInstanceOf(ProcessingException.class).hasMessage("invalid timestamp");
        assertThatThrownBy(() -> OperationTimestamp.parse("NaN"))
                .isInstanceOf(ProcessingException.class).hasMessage("invalid timestamp");
    }

    @Test
    void formatsLikeGoFormatFloat() {
        assertThat(OperationTimestamp.formatSeconds(0)).isEqualTo("0");
        assertThat(OperationTimestamp.formatSeconds(1.5)).isEqualTo("1.5");
        assertThat(OperationTimestamp.formatSeconds(0.25)).isEqualTo("0.25");
        assertThat(OperationTimestamp.speedPts(1.5)).isEqualTo("0.67*PTS");
        assertThat(OperationTimestamp.speedPts(0.5)).isEqualTo("2.00*PTS");
    }
}
