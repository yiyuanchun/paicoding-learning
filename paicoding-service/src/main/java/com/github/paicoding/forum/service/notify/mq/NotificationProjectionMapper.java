package com.github.paicoding.forum.service.notify.mq;

import com.github.paicoding.forum.service.notify.repository.entity.NotifyMsgDO;
import org.apache.ibatis.annotations.*;

@Mapper
public interface NotificationProjectionMapper {
    @Insert("INSERT INTO notify_msg(notification_key,related_id,notify_user_id,operate_user_id,type,state,msg,visible,last_event_version) VALUES (#{notificationKey},#{relatedId},#{notifyUserId},#{operateUserId},#{type},0,'',0,0) ON DUPLICATE KEY UPDATE notification_key=VALUES(notification_key)")
    void ensure(NotifyMsgDO msg);

    @Update("UPDATE notify_msg SET related_id=#{relatedId},comment_id=#{commentId},msg=#{msg},visible=#{visible},last_event_key=#{lastEventKey},state=CASE WHEN #{visible}=1 THEN 0 ELSE state END,create_time=CASE WHEN #{visible}=1 THEN CURRENT_TIMESTAMP ELSE create_time END,last_event_version=#{lastEventVersion} WHERE notification_key=#{notificationKey} AND last_event_version<#{lastEventVersion}")
    int apply(NotifyMsgDO msg);
}
