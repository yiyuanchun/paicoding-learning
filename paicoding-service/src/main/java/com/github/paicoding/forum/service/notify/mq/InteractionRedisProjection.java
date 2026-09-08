package com.github.paicoding.forum.service.notify.mq;

import com.github.paicoding.forum.api.model.vo.notify.InteractionMessage;
import com.github.paicoding.forum.core.util.DateUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import static com.github.paicoding.forum.service.statistics.constants.CountConstants.*;

@Service
@RequiredArgsConstructor
public class InteractionRedisProjection {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> STATS = script("lua/mq_interaction_statistics.lua");
    private static final DefaultRedisScript<Long> ACTIVITY = script("lua/mq_interaction_activity.lua");

    private static DefaultRedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }

    public void statistics(InteractionMessage event) {
        String first, second, field, secondField;
        switch (event.getKind()) {
            case COMMENT:
                first = ARTICLE_STATISTIC_INFO + event.getArticleId();
                second = first;
                field = COMMENT_COUNT;
                secondField = "";
                break;
            case FOLLOW:
                first = USER_STATISTIC_INFO + event.getReceiverId();
                second = USER_STATISTIC_INFO + event.getActorId();
                field = FANS_COUNT;
                secondField = FOLLOW_COUNT;
                break;
            default:
                if ("COMMENT".equals(event.getTargetType())) {
                    // A like on a comment must not increase the article author's like count.
                    first = "comment_statistic_" + event.getTargetId();
                    second = first;
                    field = PRAISE_COUNT;
                    secondField = "";
                } else {
                    first = USER_STATISTIC_INFO + event.getReceiverId();
                    second = ARTICLE_STATISTIC_INFO + event.getArticleId();
                    field = event.getKind() == InteractionMessage.Kind.PRAISE ? PRAISE_COUNT : COLLECTION_COUNT;
                    secondField = field;
                }
        }
        int delta = (event.isActive() ? 1 : 0) - (event.isPreviousActive() ? 1 : 0);
        redis.execute(STATS, Arrays.asList("mq:statistics:events", first, second),
                event.getEventKey(), String.valueOf(delta), field, secondField);
    }

    public void activity(InteractionMessage event) {
        String day = DateUtil.format(DateTimeFormatter.ofPattern("yyyyMMdd"), event.getOccurredAt());
        String month = DateUtil.format(DateTimeFormatter.ofPattern("yyyyMM"), event.getOccurredAt());
        String field;
        switch (event.getKind()) {
            case COMMENT: field = "comment_" + event.getTargetId() + "_rate"; break;
            case FOLLOW: field = event.getReceiverId() + "_follow"; break;
            default:
                field = ("COMMENT".equals(event.getTargetType()) ? "comment_" : "") + event.getTargetId()
                        + (event.getKind() == InteractionMessage.Kind.PRAISE ? "_praise" : "_collect");
        }
        int score = !event.isActive() ? 0 : event.getKind() == InteractionMessage.Kind.COMMENT ? 3 : 2;
        redis.execute(ACTIVITY, Arrays.asList("mq:activity:versions",
                        "activity_rank_" + event.getActorId() + day, "activity_rank_" + day, "activity_rank_" + month),
                day + ":" + event.getAggregateKey(), String.valueOf(event.getAggregateVersion()), field,
                String.valueOf(event.getActorId()), String.valueOf(score),
                String.valueOf(31 * DateUtil.ONE_DAY_SECONDS), String.valueOf(12 * DateUtil.ONE_MONTH_SECONDS));
    }
}
