package kr.lit.api;

import kr.lit.auth.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.http.HttpStatus;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class ReportArchiveJobService {
    private final ReportArchiveJobRepository repository;private final ReportArchiveRepository scopes;private final ReportArchiveService archives;
    public ReportArchiveJobService(ReportArchiveJobRepository repository,ReportArchiveRepository scopes,ReportArchiveService archives){this.repository=repository;this.scopes=scopes;this.archives=archives;}
    public record View(String id,String status,String errorCode,Instant createdAt,Instant completedAt,Instant expiresAt,String downloadUrl) {}
    public View createJob(String scope,boolean assessment,String classId,String format,boolean raw){var user=AccountAccess.require(AuthModels.Role.TEACHER);var parsed=archives.parseFormat(format);if(scopes.getTargets(scope,assessment,classId).size()>200)throw new ApiError.Failure(HttpStatus.PAYLOAD_TOO_LARGE,"REPORT_ARCHIVE_LIMIT","반별로 나누어 요청해주세요.");return getJob(repository.createJob(user.userId(),scope,assessment,classId,parsed.name(),raw));}
    private ReportArchiveJobRepository.Job requireJob(String id){var user=AccountAccess.require(AuthModels.Role.TEACHER);var job=repository.getJob(id).filter(j->j.userId().equals(user.userId())).orElseThrow(()->new ApiError.Failure(HttpStatus.NOT_FOUND,"ARCHIVE_JOB_NOT_FOUND","ZIP 작업을 찾을 수 없습니다."));scopes.getTargets(job.scopeId(),job.assessment(),job.classId());return job;}
    public View getJob(String id){var job=requireJob(id);Instant expiry=job.completedAt()==null?null:job.completedAt().plusSeconds(3600);boolean valid=job.status().equals("DONE")&&expiry.isAfter(Instant.now())&&job.path()!=null&&Files.isRegularFile(Path.of(job.path()));String status=job.status().equals("DONE")&&!valid?"EXPIRED":job.status();return new View(id,status,job.errorCode(),job.createdAt(),job.completedAt(),expiry,valid?"/api/teacher/report-archive-jobs/"+id+"/download":null);}
    public ReportArchiveService.Archive getDownload(String id){var job=requireJob(id);if(!getJob(id).status().equals("DONE"))throw new ApiError.Failure(job.status().equals("DONE")||job.status().equals("EXPIRED")?HttpStatus.GONE:HttpStatus.CONFLICT,"ARCHIVE_NOT_READY","ZIP이 준비되지 않았거나 만료됐습니다.");return new ReportArchiveService.Archive(Path.of(job.path()),"lit-reports-"+job.format().toLowerCase(Locale.ROOT)+".zip");}
    @Scheduled(fixedDelayString="${lit.archive.interval-ms:3000}",initialDelayString="${lit.archive.interval-ms:3000}")
    public void processPending(){repository.recoverJobs();for(var job:repository.getPending())processJob(job.id());for(var job:repository.getExpired()){try{if(job.path()!=null)Files.deleteIfExists(Path.of(job.path()));repository.expireJob(job.id());}catch(Exception ignored){}}
        try(var files=Files.newDirectoryStream(Path.of(System.getProperty("java.io.tmpdir")),"lit-report-job-*.zip")){for(var file:files)if(Files.getLastModifiedTime(file).toInstant().isBefore(Instant.now().minusSeconds(3600)))Files.deleteIfExists(file);}catch(Exception ignored){}
    }
    public void processJob(String id){if(!repository.claimJob(id))return;var job=repository.getJob(id).orElseThrow();var previous=SecurityContextHolder.getContext();Path file=null;
        try {if(!repository.isActive(job.userId()))throw new ApiError.Failure(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","계정 상태를 확인해주세요.");var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(new UsernamePasswordAuthenticationToken(new AuthModels.Principal(job.userId(),AuthModels.Role.TEACHER,"archive-worker"),null,List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));SecurityContextHolder.setContext(context);
            var archive=archives.createArchive(job.scopeId(),job.assessment(),job.classId(),archives.parseFormat(job.format()),job.raw());file=archive.path();var target=file.resolveSibling("lit-report-job-"+id+".zip");Files.move(file,target);file=target;repository.completeJob(id,target.toString());
        }catch(Exception failure){if(file!=null)try{Files.deleteIfExists(file);}catch(Exception ignored){}repository.failJob(id,failure instanceof ApiError.Failure api?api.code:"REPORT_EXPORT_FAILED");}finally{SecurityContextHolder.setContext(previous);}
    }
}
