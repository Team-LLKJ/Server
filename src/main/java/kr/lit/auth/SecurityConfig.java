package kr.lit.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import kr.lit.api.ApiError;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() {return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();}
    @Bean UserDetailsService unusedFormLoginUsers() {return username->{throw new UsernameNotFoundException("Bearer authentication only");};}
    @Bean org.springframework.web.cors.CorsConfigurationSource corsConfiguration(@Value("${lit.cors.allowed-origins:}") String origins) {
        var source=new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        var allowed=Arrays.stream(origins.split(",")).map(String::strip).filter(v->!v.isEmpty()).toList();
        if(allowed.contains("*"))throw new IllegalArgumentException("CORS requires explicit frontend origins");
        if(!allowed.isEmpty()) {
            var config=new org.springframework.web.cors.CorsConfiguration();
            config.setAllowedOrigins(allowed);config.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
            config.setAllowedHeaders(List.of("Authorization","Content-Type","X-Participant-Token"));
            config.setExposedHeaders(List.of("Content-Disposition"));config.setAllowCredentials(false);config.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**",config);
        }
        return source;
    }
    @Bean SecurityFilterChain security(HttpSecurity http,AuthService auth,ParticipantAccess participants,ObjectMapper json,
            org.springframework.web.cors.CorsConfigurationSource corsConfiguration) throws Exception {
        return http.cors(cors->cors.configurationSource(corsConfiguration))
            // Tokens are accepted only in explicit headers/JSON, never auto-sent cookies.
            .csrf(csrf->csrf.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)->error(res,json,401,"AUTH_REQUIRED","로그인이 필요합니다."))
                .accessDeniedHandler((req,res,ex)->error(res,json,403,"ACCESS_DENIED","접근 권한이 없습니다.")))
            // Retired reset URLs remain permitAll so missing handlers return 404.
            .authorizeHttpRequests(a->a
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health","/actuator/health/readiness","/actuator/health/liveness").permitAll()
                .requestMatchers("/api/auth/signup/student","/api/auth/signup/teacher","/api/auth/email-verifications","/api/auth/email-verifications/confirm","/api/auth/username-availability","/api/auth/login","/api/auth/refresh","/api/auth/password-reset/request","/api/auth/password-reset/confirm","/api/participation/verify","/api/participation/join","/api/participation/reconnect").permitAll()
                .requestMatchers("/api/auth/teacher-invitations","/api/teacher/**").hasRole("TEACHER")
                .requestMatchers("/api/evaluations/**","/api/student/evaluations/**").hasRole("PARTICIPANT")
                .requestMatchers("/api/notifications","/api/notifications/**","/api/users/me","/api/users/me/**").hasRole("TEACHER")
                .requestMatchers("/api/auth/me","/api/auth/logout").authenticated().anyRequest().denyAll())
            .addFilterBefore(new BearerFilter(auth,participants,json),UsernamePasswordAuthenticationFilter.class).build();
    }
    private static void error(HttpServletResponse response,ObjectMapper json,int status,String code,String message) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(),Map.of("code",code,"message",message));
    }
    private static class BearerFilter extends OncePerRequestFilter {
        private final AuthService auth;private final ParticipantAccess participants;private final ObjectMapper json;
        private record Window(long expires,int count) {}
        private final Map<String,Window> attempts=new HashMap<>();
        BearerFilter(AuthService auth,ParticipantAccess participants,ObjectMapper json) {this.auth=auth;this.participants=participants;this.json=json;}
        private synchronized boolean limit(String ip,String group,int max) {
            long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->e.getValue().expires()<=now);
            String key=ip+":"+group;var prior=attempts.get(key);
            if(prior==null&&attempts.size()>=10000)return false;
            if(prior!=null&&prior.count()>=max)return false;
            attempts.put(key,new Window(prior==null?now+60000:prior.expires(),prior==null?1:prior.count()+1));return true;
        }
        protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
            res.setHeader("Cache-Control","no-store");String path=req.getRequestURI().substring(req.getContextPath().length());
            int max=(path.equals("/api/users/me/password")||path.startsWith("/api/users/me/email/")||(path.equals("/api/users/me")&&req.getMethod().equals("DELETE")))?10:path.contains("signup")?20:30;
            boolean limited=path.startsWith("/api/auth/")&&!path.equals("/api/auth/me")&&!path.equals("/api/auth/logout")||path.startsWith("/api/participation/")||path.equals("/api/users/me/password")||path.startsWith("/api/users/me/email/")||(path.equals("/api/users/me")&&req.getMethod().equals("DELETE"));
            String group=path.startsWith("/api/participation/")?"pin":path.contains("signup")?"signup":path;
            if(limited&&!limit(req.getRemoteAddr(),group,max)) {error(res,json,429,"RATE_LIMITED","요청이 많습니다. 잠시 후 다시 시도해주세요.");return;}
            boolean studentPath=path.startsWith("/api/student/evaluations/");
            boolean guestPath=path.startsWith("/api/evaluations/")||studentPath;
            if(guestPath) {
                try {
                    String[] segments=path.split("/");
                    int idIndex=studentPath?4:3;
                    if(segments.length<=idIndex) {error(res,json,404,"EVALUATION_NOT_FOUND","평가를 찾을 수 없습니다.");return;}
                    var guest=participants.authenticate(segments[idIndex],req.getHeader("X-Participant-Token"));
                    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(guest,null,List.of(new SimpleGrantedAuthority("ROLE_PARTICIPANT"))));
                } catch(ApiError.Failure failure) {error(res,json,failure.status.value(),failure.code,failure.getMessage());return;}
                chain.doFilter(req,res);return;
            }
            // Participation endpoints are public and do not consume account credentials.
            if(path.startsWith("/api/participation/")) {chain.doFilter(req,res);return;}
            String header=req.getHeader("Authorization");
            if(header!=null) {
                if(!header.matches("Bearer [A-Za-z0-9_-]{43}")) {error(res,json,401,"INVALID_ACCESS_TOKEN","유효하지 않은 로그인 토큰입니다.");return;}
                try {
                    var p=auth.authenticate(header.substring(7));
                    var authentication=new UsernamePasswordAuthenticationToken(p,null,List.of(new SimpleGrantedAuthority("ROLE_"+p.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } catch(ApiError.Failure failure) {error(res,json,failure.status.value(),failure.code,failure.getMessage());return;}
            }
            chain.doFilter(req,res);
        }
    }
}
