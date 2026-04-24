package com.herald.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaAttachmentTest {

    @Test
    void constructsValidImageAttachment() {
        var att = new MediaAttachment("image/jpeg", new byte[]{1, 2, 3}, "photo");
        assertThat(att.mimeType()).isEqualTo("image/jpeg");
        assertThat(att.sizeBytes()).isEqualTo(3);
        assertThat(att.isImage()).isTrue();
        assertThat(att.isAudio()).isFalse();
        assertThat(att.label()).isEqualTo("photo");
    }

    @Test
    void audioAttachmentDetection() {
        var att = new MediaAttachment("audio/ogg", new byte[]{1}, "voice");
        assertThat(att.isAudio()).isTrue();
        assertThat(att.isImage()).isFalse();
    }

    @Test
    void rejectsBlankMimeType() {
        assertThatThrownBy(() -> new MediaAttachment("", new byte[]{1}, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MediaAttachment(null, new byte[]{1}, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullData() {
        assertThatThrownBy(() -> new MediaAttachment("image/jpeg", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsBlankLabel() {
        var att = new MediaAttachment("image/jpeg", new byte[]{1}, null);
        assertThat(att.label()).isEqualTo("attachment");
    }
}
