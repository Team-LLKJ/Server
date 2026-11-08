package kr.lit.api;

import kr.lit.auth.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ClassEventService {
    private final AuthService auth;private final EvaluationService evaluations;private final ClassEventRepository repository;
    private final Map<SseEmitter,Connection> connections=new ConcurrentHashMap<>();
    private static class Connection {final String id,token,userId;long cursor;int ticks;Connection(String id,String token,String userId,long cursor){this.id=id;this.token=token;this.userId=userId;this.cursor=cursor;}}
    public ClassEventService(AuthService auth,EvaluationService evaluations,ClassEventRepository repository){this.auth=auth;this.evaluations=evaluations;this.repository=repository;}
    public SseEmitter openStream(String id,String token,long cursor){
        var principal=AccountAccess.require(AuthModels.Role.TEACHER);evaluations.details(id);
        synchronized(connections){if(connections.size()>=200||connections.values().stream().filter(c->c.userId.equals(principal.userId())).count()>=10)throw new ApiError.Failure(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,"SSE_LIMIT","열린 실시간 연결이 너무 많습니다.");
            var emitter=new SseEmitter(120000L);connections.put(emitter,new Connection(id,token,principal.userId(),cursor));emitter.onCompletion(()->connections.remove(emitter));emitter.onTimeout(()->{connections.remove(emitter);emitter.complete();});emitter.onError(error->connections.remove(emitter));return emitter;}
    }
    @Scheduled(fixedDelayString="${lit.events.interval-ms:1000}")
    public void publishEvents(){for(var entry:connections.entrySet()){
        var previous=SecurityContextHolder.getContext();var connection=entry.getValue();var emitter=entry.getKey();
        try {
            var principal=auth.authenticate(connection.token);var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));SecurityContextHolder.setContext(context);
            evaluations.details(connection.id);
            var events=repository.getEvents(connection.id,connection.cursor);
            for(var event:events){emitter.send(SseEmitter.event().id(Long.toString(event.eventId())).name(switch(event.type()){case "SUBMITTED"->"submission";case "PARTICIPANT_EXPELLED"->"participant-expelled";default->"warning";}).data(event));connection.cursor=event.eventId();}
            if(++connection.ticks%20==0)emitter.send(SseEmitter.event().comment("keepalive"));
        }catch(Exception failure){connections.remove(emitter);emitter.complete();}finally{SecurityContextHolder.setContext(previous);}
    }}
}
