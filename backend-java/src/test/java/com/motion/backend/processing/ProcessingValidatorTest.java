package com.motion.backend.processing;

import com.motion.backend.media.MediaRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessingValidatorTest {

    @Test
    void rejectsEmptyAndOversizedOperationLists() {
        assertThatThrownBy(() -> validate(video(10), new ProcessingRequest(List.of())))
                .isInstanceOf(ProcessingException.class).hasMessage("operations cannot be empty");

        List<Operation> tooMany = IntStream.range(0, ProcessingValidator.MAX_OPERATIONS + 1)
                .mapToObj(i -> new Operation("flip", null, null, null, 0, null, null,
                        null, null, null, null, null))
                .toList();
        assertThatThrownBy(() -> validate(video(10), new ProcessingRequest(tooMany)))
                .isInstanceOf(ProcessingException.class).hasMessage("too many operations max=20");
    }

    @Test
    void validatesFlipSpeedAndVolumeWithGoMessages() {
        assertInvalid(operation("flip").orientation(2).build(),
                "operation 0 flip invalid: direction must be vertical or horizontal");
        assertInvalid(operation("speed").factor(0.49).build(),
                "operation 0 speed invalid: speed factor must be between 0.50 and 2.00");
        assertInvalid(operation("speed").factor(2.01).build(),
                "operation 0 speed invalid: speed factor must be between 0.50 and 2.00");
        assertInvalid(operation("volume").factor(0D).build(),
                "operation 0 volume invalid: volume factor must be greater than 0");

        validate(video(10), request(operation("speed").factor(0.5).build()));
        validate(video(10), request(operation("speed").factor(2.0).build()));
    }

    @Test
    void validatesTrimTimestampsAgainstSourceDuration() {
        assertInvalid(operation("trim").start("bad").end("1").build(),
                "operation 0 trim invalid: invalid start timestamp");
        assertInvalid(operation("slice").start("1").end("bad").build(),
                "operation 0 slice invalid: invalid end timestamp");
        assertInvalid(operation("remove_section").start("2").end("1").build(),
                "operation 0 remove_section invalid: start must be before end");
        assertInvalid(operation("trim").start("0").end("20").build(),
                "operation 0 trim invalid: trim end exceeds video duration");
        validate(video(10), request(operation("trim").start("00:00:01").end("00:00:02").build()));
    }

    @Test
    void validatesAudioReferencesAndTimeWindows() {
        assertInvalid(operation("add_audio").build(),
                "operation 0 add_audio invalid: an audio source video is required");
        assertInvalid(operation("replace_audio").audioSourceId("other").audioVolume(11D).build(),
                "operation 0 replace_audio invalid: audio volume is too large");
        assertInvalid(operation("add_audio").audioSourceId("other").audioStart("2").audioEnd("1").build(),
                "operation 0 add_audio invalid: audio start must be before audio end");
        assertInvalid(operation("add_audio").audioSourceId("other").offset("bad").build(),
                "operation 0 add_audio invalid: invalid offset");
        validate(video(10), request(operation("add_audio").audioSourceId("other")
                .audioVolume(0D).offset("0").audioStart("0").audioEnd("1").build()));
    }

    @Test
    void validatesConcatAndToAudioPlacement() {
        assertInvalid(operation("concat").build(),
                "operation 0 concat invalid: concat requires at least two clips");
        assertInvalid(operation("concat").clips(List.of(
                        new Clip(null, "0", "1"), new Clip(null, "2", "1"))).build(),
                "operation 0 concat invalid: clip 1 start must be before end");
        assertInvalid(request(operation("to_audio").build(), operation("flip").build()),
                "operation 0 to_audio invalid: extract audio must be the last operation");
        assertInvalid(request(operation("to_audio").build(), operation("to_audio").build()),
                "operation 1 to_audio invalid: only one extract audio operation is allowed");
        assertInvalid(request(operation("flip").build(), operation("concat").clips(List.of(
                        new Clip(null, "0", "1"), new Clip(null, "0", "1"))).build()),
                "concat must be the only operation");
        assertInvalid(operation("compose").build(),
                "operation 0 unknown type=compose");
    }

    @Test
    void collectsReferencedVideoIdsInFirstSeenOrder() {
        ProcessingRequest request = request(
                operation("add_audio").audioSourceId("audio").build(),
                operation("concat").clips(List.of(
                        new Clip("primary", "0", "1"),
                        new Clip("clip", "0", "1"),
                        new Clip("audio", "0", "1"))).build());
        assertThat(ProcessingValidator.referencedVideoIds("primary", request))
                .containsExactly("audio", "clip");
    }

    private static void assertInvalid(Operation operation, String message) {
        assertInvalid(request(operation), message);
    }

    private static void assertInvalid(ProcessingRequest request, String message) {
        assertThatThrownBy(() -> validate(video(10), request))
                .isInstanceOf(ProcessingException.class).hasMessage(message);
    }

    private static void validate(MediaRecord video, ProcessingRequest request) {
        ProcessingValidator.validate(video, request);
    }

    private static ProcessingRequest request(Operation... operations) {
        return new ProcessingRequest(List.of(operations));
    }

    private static Builder operation(String type) {
        return new Builder(type);
    }

    private static MediaRecord video(double duration) {
        return new MediaRecord("primary", "/tmp/primary.mp4", "primary.mp4", 1, null,
                "uploaded", 0, null, duration, 160, 120, null, null, "user", false, null, false, null);
    }

    private static final class Builder {
        private final String type;
        private String start;
        private String end;
        private Double factor;
        private Integer orientation;
        private String audioSourceId;
        private Double audioVolume;
        private String offset;
        private String audioStart;
        private String audioEnd;
        private List<Clip> clips;

        private Builder(String type) {
            this.type = type;
        }

        private Builder start(String value) {
            start = value;
            return this;
        }

        private Builder end(String value) {
            end = value;
            return this;
        }

        private Builder factor(double value) {
            factor = value;
            return this;
        }

        private Builder orientation(int value) {
            orientation = value;
            return this;
        }

        private Builder audioSourceId(String value) {
            audioSourceId = value;
            return this;
        }

        private Builder audioVolume(double value) {
            audioVolume = value;
            return this;
        }

        private Builder offset(String value) {
            offset = value;
            return this;
        }

        private Builder audioStart(String value) {
            audioStart = value;
            return this;
        }

        private Builder audioEnd(String value) {
            audioEnd = value;
            return this;
        }

        private Builder clips(List<Clip> value) {
            clips = value;
            return this;
        }

        private Operation build() {
            return new Operation(type, start, end, factor, orientation, audioSourceId, audioVolume,
                    offset, audioStart, audioEnd, clips, null);
        }
    }
}
