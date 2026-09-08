package com.github.paicoding.forum.service.notify.mq;

import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import com.github.paicoding.forum.service.comment.repository.entity.CommentDO;
import com.github.paicoding.forum.service.user.repository.entity.UserFootDO;

public final class InteractionMessages {
    private InteractionMessages() { }

    public static InteractionMessage comment(CommentDO comment, Long articleAuthor, Long parentAuthor, boolean active) {
        InteractionMessage event = new InteractionMessage();
        event.setKind(InteractionMessage.Kind.COMMENT);
        event.setActorId(comment.getUserId());
        event.setReceiverId(articleAuthor);
        event.setTargetType("COMMENT");
        event.setTargetId(comment.getId());
        event.setArticleId(comment.getArticleId());
        if (parentAuthor != null && parentAuthor > 0) {
            event.setParentCommentId(comment.getParentCommentId());
            event.setParentUserId(parentAuthor);
        }
        event.setContent(comment.getContent());
        event.setActive(active);
        event.setPreviousActive(!active);
        return event;
    }

    public static InteractionMessage foot(UserFootDO foot, InteractionMessage.Kind kind,
                                          Long articleId, boolean before, boolean active) {
        InteractionMessage event = new InteractionMessage();
        event.setKind(kind);
        event.setActorId(foot.getUserId());
        event.setReceiverId(foot.getDocumentUserId());
        event.setTargetType(foot.getDocumentType() == 1 ? "ARTICLE" : "COMMENT");
        event.setTargetId(foot.getDocumentId());
        event.setArticleId(articleId);
        event.setPreviousActive(before);
        event.setActive(active);
        return event;
    }

    public static InteractionMessage follow(Long actor, Long receiver, boolean before, boolean active) {
        InteractionMessage event = new InteractionMessage();
        event.setKind(InteractionMessage.Kind.FOLLOW);
        event.setActorId(actor);
        event.setReceiverId(receiver);
        event.setTargetType("USER");
        event.setTargetId(receiver);
        event.setPreviousActive(before);
        event.setActive(active);
        return event;
    }
}
