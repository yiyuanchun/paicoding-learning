package com.github.paicoding.forum.service.notify.mq;

import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import com.github.paicoding.forum.service.notify.repository.entity.NotifyMsgDO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Inbox and projection share one database transaction; the listener ACKs after this proxy returns. */
@Service
@RequiredArgsConstructor
public class NotificationConsumerService {
    private final InteractionMqMapper mq;
    private final NotificationProjectionMapper projection;

    @Transactional(rollbackFor = Exception.class)
    public List<NotifyMsgDO> consume(InteractionMessage event) {
        event.validate();
        String lock = "notify:" + event.getAggregateKey();
        mq.ensureState(lock);
        mq.lockState(lock);
        if (mq.consumed("notification", event.getEventKey()) != 0) {
            return Collections.emptyList();
        }
        mq.consume("notification", event.getEventKey());
        List<NotifyMsgDO> changes = new ArrayList<>();
        switch (event.getKind()) {
            case COMMENT:
                apply(event, event.getReceiverId(), 1, changes);
                if (event.getParentUserId() != null) {
                    apply(event, event.getParentUserId(), 2, changes);
                }
                break;
            case PRAISE: apply(event, event.getReceiverId(), 3, changes); break;
            case COLLECT: apply(event, event.getReceiverId(), 4, changes); break;
            case FOLLOW: apply(event, event.getReceiverId(), 5, changes); break;
            default: throw new IllegalArgumentException("Unknown interaction");
        }
        return changes;
    }

    private void apply(InteractionMessage event, Long receiver, int type, List<NotifyMsgDO> changes) {
        NotifyMsgDO msg = new NotifyMsgDO()
                .setNotificationKey(event.getAggregateKey() + ":receiver:" + receiver + ":type:" + type)
                .setNotifyUserId(receiver).setOperateUserId(event.getActorId())
                .setRelatedId(event.getArticleId() == null ? 0L : event.getArticleId())
                .setCommentId("COMMENT".equals(event.getTargetType()) ? event.getTargetId() : null)
                .setType(type).setState(0).setVisible(event.isActive() ? 1 : 0)
                .setLastEventVersion(event.getAggregateVersion()).setLastEventKey(event.getEventKey())
                .setMsg(event.getKind() == InteractionMessage.Kind.COMMENT ? event.getContent() : "");
        if (event.getKind() == InteractionMessage.Kind.PRAISE && "COMMENT".equals(event.getTargetType())) {
            msg.setMsg("赞了您在文章下的评论");
        }
        projection.ensure(msg);
        if (projection.apply(msg) == 1) {
            changes.add(msg);
        }
    }
}
