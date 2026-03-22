package com.javaagent.commons;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 스팬 링크 설정
 *
 * 소스 스팬과 타겟 스팬 간의 연결 규칙 정의
 */
public class SpanLinkConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String linkId;
    private String sourceSpanName;
    private String sourceAttributeKey;
    private String targetSpanName;
    private String targetAttributeKey;
    private LinkType linkType;
    private boolean enabled;
    private Instant createdAt;

    public enum LinkType {
        PARENT_TO_CHILD,
        CHILD_TO_PARENT,
        RELATED_SPAN
    }

    // Constructors
    public SpanLinkConfig() {
        this.createdAt = Instant.now();
        this.enabled = true;
        this.linkType = LinkType.PARENT_TO_CHILD;
    }

    public SpanLinkConfig(String linkId, String sourceSpanName, String sourceAttributeKey,
                         String targetSpanName, String targetAttributeKey) {
        this();
        this.linkId = linkId;
        this.sourceSpanName = sourceSpanName;
        this.sourceAttributeKey = sourceAttributeKey;
        this.targetSpanName = targetSpanName;
        this.targetAttributeKey = targetAttributeKey;
    }

    // Builder
    public static class Builder {
        private String linkId;
        private String sourceSpanName;
        private String sourceAttributeKey;
        private String targetSpanName;
        private String targetAttributeKey;
        private LinkType linkType = LinkType.PARENT_TO_CHILD;
        private boolean enabled = true;

        public Builder linkId(String id) { this.linkId = id; return this; }
        public Builder sourceSpanName(String name) { this.sourceSpanName = name; return this; }
        public Builder sourceAttributeKey(String key) { this.sourceAttributeKey = key; return this; }
        public Builder targetSpanName(String name) { this.targetSpanName = name; return this; }
        public Builder targetAttributeKey(String key) { this.targetAttributeKey = key; return this; }
        public Builder linkType(LinkType type) { this.linkType = type; return this; }
        public Builder enabled(boolean enabled) { this.enabled = enabled; return this; }

        public SpanLinkConfig build() {
            return new SpanLinkConfig(linkId, sourceSpanName, sourceAttributeKey,
                                    targetSpanName, targetAttributeKey)
                .setLinkType(linkType)
                .setEnabled(enabled);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters and Setters
    public String getLinkId() { return linkId; }
    public SpanLinkConfig setLinkId(String linkId) { this.linkId = linkId; return this; }

    public String getSourceSpanName() { return sourceSpanName; }
    public SpanLinkConfig setSourceSpanName(String sourceSpanName) {
        this.sourceSpanName = sourceSpanName;
        return this;
    }

    public String getSourceAttributeKey() { return sourceAttributeKey; }
    public SpanLinkConfig setSourceAttributeKey(String sourceAttributeKey) {
        this.sourceAttributeKey = sourceAttributeKey;
        return this;
    }

    public String getTargetSpanName() { return targetSpanName; }
    public SpanLinkConfig setTargetSpanName(String targetSpanName) {
        this.targetSpanName = targetSpanName;
        return this;
    }

    public String getTargetAttributeKey() { return targetAttributeKey; }
    public SpanLinkConfig setTargetAttributeKey(String targetAttributeKey) {
        this.targetAttributeKey = targetAttributeKey;
        return this;
    }

    public LinkType getLinkType() { return linkType; }
    public SpanLinkConfig setLinkType(LinkType linkType) {
        this.linkType = linkType;
        return this;
    }

    public boolean isEnabled() { return enabled; }
    public SpanLinkConfig setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    public Instant getCreatedAt() { return createdAt; }
    public SpanLinkConfig setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SpanLinkConfig that = (SpanLinkConfig) o;
        return Objects.equals(linkId, that.linkId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(linkId);
    }

    @Override
    public String toString() {
        return "SpanLinkConfig{" +
                "linkId='" + linkId + '\'' +
                ", sourceSpanName='" + sourceSpanName + '\'' +
                ", sourceAttributeKey='" + sourceAttributeKey + '\'' +
                ", targetSpanName='" + targetSpanName + '\'' +
                ", targetAttributeKey='" + targetAttributeKey + '\'' +
                ", linkType=" + linkType +
                ", enabled=" + enabled +
                '}';
    }
}
