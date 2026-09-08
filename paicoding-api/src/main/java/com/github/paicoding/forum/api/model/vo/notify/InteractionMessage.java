package com.github.paicoding.forum.api.model.vo.notify;

import lombok.Data;
import java.io.Serializable;

/** Versioned business fact. Retrying delivery must never change eventKey. */
@Data
public class InteractionMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Kind { COMMENT, PRAISE, COLLECT, FOLLOW }
    private int schemaVersion = 1;
    private String eventKey;
    private String aggregateKey;
    private long aggregateVersion;
    private Kind kind;
    private Long actorId;
    private Long receiverId;
    private String targetType;
    private Long targetId;
    private Long articleId;
    private Long parentCommentId;
    private Long parentUserId;
    private boolean active;
    private boolean previousActive;
    private long occurredAt;
    private String content;

    public String businessKey() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" +
                targetType.toLowerCase(java.util.Locale.ROOT) + ":" + targetId +
                (kind == Kind.COMMENT ? "" : ":actor:" + actorId);
    }

    public void validate() {
        if (schemaVersion != 1 || kind == null || actorId == null || actorId <= 0 ||
                receiverId == null || receiverId <= 0 || targetId == null || targetId <= 0 ||
                aggregateVersion <= 0 || occurredAt <= 0 ||
                !("ARTICLE".equals(targetType) || "COMMENT".equals(targetType) || "USER".equals(targetType))) {
            throw new IllegalArgumentException("Invalid interaction message");
        }
        if ((kind == Kind.FOLLOW) != "USER".equals(targetType) ||
                (kind == Kind.COMMENT && !"COMMENT".equals(targetType)) ||
                (kind == Kind.COLLECT && !"ARTICLE".equals(targetType)) ||
                (kind != Kind.FOLLOW && (articleId == null || articleId <= 0)) ||
                ((parentUserId == null) != (parentCommentId == null))) {
            throw new IllegalArgumentException("Invalid interaction target");
        }
        if (!businessKey().equals(aggregateKey) || !(aggregateKey + ":v" + aggregateVersion).equals(eventKey)) {
            throw new IllegalArgumentException("Business key/version mismatch");
        }
    }
}
