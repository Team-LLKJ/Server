package kr.lit.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/teacher/evaluations/{id}")
class ClassEventController {
    private final ClassEventService events;
    ClassEventController(ClassEventService events){this.events=events;}
    @GetMapping(value="/live-events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> getEvents(@PathVariable String id,@RequestHeader("Authorization") String authorization,@RequestHeader(value="Last-Event-ID",required=false) Long lastId,@RequestParam(defaultValue="0") long afterId){
        long cursor=lastId==null?afterId:lastId;if(cursor<0)throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REQUEST","이벤트 커서는0 이상이어야 합니다.");
        return ResponseEntity.ok().header("X-Accel-Buffering","no").body(events.openStream(id,authorization.substring(7),cursor));
    }
}
