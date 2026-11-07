package kr.lit.api;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class NotificationController {
    private final NotificationService notifications;
    private final NotificationDeletionService deletion;
    NotificationController(NotificationService notifications,NotificationDeletionService deletion) { this.notifications=notifications;this.deletion=deletion; }
    record SettingsInput(@com.fasterxml.jackson.annotation.JsonProperty("notify") Boolean notifyEnabled,Boolean sound) {}

    @GetMapping("/notifications")
    NotificationService.Page list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,
                                  @RequestParam(defaultValue="false") boolean unreadOnly) {
        return notifications.list(page,size,unreadOnly);
    }
    @GetMapping("/notifications/unread-count")
    NotificationService.UnreadCount unread() { return notifications.unreadCount(); }
    @PatchMapping("/notifications/{id}/read")
    NotificationService.Notification read(@PathVariable long id) { return notifications.read(id); }
    @PostMapping("/notifications/read-all")
    NotificationService.ReadAll readAll() { return notifications.readAll(); }
    @DeleteMapping("/notifications/{id}")
    NotificationDeletionService.DeleteResponse deleteNotification(@PathVariable long id) {return deletion.deleteNotification(id);}
    @DeleteMapping("/notifications")
    NotificationDeletionService.DeleteResponse deleteNotifications() {return deletion.deleteNotifications();}
    @GetMapping("/users/me/settings")
    NotificationService.Settings settings() { return notifications.settings(); }
    @PutMapping("/users/me/settings")
    NotificationService.Settings save(@RequestBody SettingsInput input) { return notifications.saveSettings(input.notifyEnabled(),input.sound()); }
}
