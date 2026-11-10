package kr.lit.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.*;

@Configuration
public class OpenApiConfig {
    private static final String TEACHER_AUTH="TeacherAccessToken";
    private static final String PARTICIPANT_AUTH="ParticipantToken";
    private static final Set<String> PUBLIC_PATHS=Set.of(
            "/api/auth/signup/student","/api/auth/signup/teacher","/api/auth/login","/api/auth/refresh",
            "/api/auth/email-verifications","/api/auth/email-verifications/confirm","/api/auth/username-availability",
            "/api/auth/password-reset/request","/api/auth/password-reset/confirm",
            "/api/participation/verify","/api/participation/join","/api/participation/reconnect");
    @Bean
    public OpenAPI createOpenApi() {
        return new OpenAPI().info(new Info().title("LIT API").version("v1")
                .description("교사 계정·수행평가·학생 비회원 참여 API. Authorize에서 교사 Access Token 또는 학생 참가자 토큰을 입력하세요. 공개 API는 토큰 없이 사용할 수 있습니다. 실제 변경 요청은 운영 데이터에 적용됩니다."))
                .servers(List.of(new Server().url("/").description("현재 문서와 같은 서버")))
                .components(new Components()
                        .addSecuritySchemes(TEACHER_AUTH,new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("POST /api/auth/login 응답의 accessToken만 입력합니다. Bearer 접두사는 자동으로 붙습니다. JWT가 아닌 opaque 토큰입니다."))
                        .addSecuritySchemes(PARTICIPANT_AUTH,new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER)
                                .name("X-Participant-Token").description("PIN 참가 응답의 participantToken을 입력합니다. 교사 로그인 토큰과 다릅니다.")));
    }
    @Bean
    public OpenApiCustomizer customizeApiAuthentication() {
        return api->api.getPaths().forEach((path,item)->item.readOperations().forEach(operation->{
            boolean student=path.startsWith("/api/evaluations/")||path.startsWith("/api/student/evaluations/");
            boolean publicApi=PUBLIC_PATHS.contains(path);
            operation.setSecurity(publicApi?List.of():List.of(new SecurityRequirement().addList(student?PARTICIPANT_AUTH:TEACHER_AUTH)));
            operation.setTags(List.of(getApiTag(path)));
            // Authorize의 값과 별도 입력 헤더가 충돌하지 않도록 인증 헤더는 스키마로만 표시합니다.
            if(operation.getParameters()!=null)operation.getParameters().removeIf(p->"header".equals(p.getIn())&&
                    ("X-Participant-Token".equalsIgnoreCase(p.getName())||"Authorization".equalsIgnoreCase(p.getName())));
            if(!publicApi)operation.getResponses().addApiResponse("401",new ApiResponse().description(student
                    ?"참가자 토큰 누락·만료·다른 평가의 토큰 (PARTICIPANT_TOKEN_REQUIRED / INVALID_PARTICIPANT_TOKEN)"
                    :"교사 로그인 필요 또는 Access Token 오류 (AUTH_REQUIRED / INVALID_ACCESS_TOKEN)"));
        }));
    }
    private static String getApiTag(String path) {
        if(path.startsWith("/api/auth/"))return "인증";
        if(path.startsWith("/api/users/me"))return "내 계정·설정";
        if(path.startsWith("/api/notifications"))return "알림";
        if(path.startsWith("/api/teacher/assessments"))return "수행평가 틀·반·공유";
        if(path.startsWith("/api/teacher/"))return "교사 평가·채점·리포트";
        if(path.startsWith("/api/participation/"))return "PIN 참여";
        return "학생 풀이·AI·제출";
    }
}
