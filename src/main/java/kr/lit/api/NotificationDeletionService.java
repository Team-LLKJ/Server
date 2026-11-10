package kr.lit.api;

import kr.lit.auth.AccountAccess;
import kr.lit.auth.AuthModels;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Transactional
public class NotificationDeletionService {
    private final NotificationRepository repository;
    private final NotificationService notifications;
    public NotificationDeletionService(NotificationRepository repository,NotificationService notifications) {
        this.repository=repository;this.notifications=notifications;
    }
    public record DeleteResponse(long deleted,long unreadCount) {}
    public DeleteResponse deleteNotification(long id) {
        String userId=AccountAccess.require(AuthModels.Role.TEACHER).userId();
        if(repository.deleteNotification(userId,id,OffsetDateTime.now(ZoneOffset.UTC))==0)
            throw new ApiError.Failure(HttpStatus.NOT_FOUND,"NOTIFICATION_NOT_FOUND","알림을 찾을 수 없습니다.");
        return new DeleteResponse(1,notifications.unreadCount().unreadCount());
    }
    public DeleteResponse deleteNotifications() {
        String userId=AccountAccess.require(AuthModels.Role.TEACHER).userId();
        long deleted=repository.deleteNotifications(userId,OffsetDateTime.now(ZoneOffset.UTC));
        return new DeleteResponse(deleted,notifications.unreadCount().unreadCount());
    }
}
