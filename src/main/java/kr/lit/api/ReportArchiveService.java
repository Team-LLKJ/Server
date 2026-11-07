package kr.lit.api;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** ZIP은 임시 파일로 구성해 전체 바이너리를 메모리에 적재하지 않습니다. */
@Service
public class ReportArchiveService {
    public enum Format { PDF,HWP }
    public record Archive(Path path,String filename) {}
    private final ReportArchiveRepository repository;private final ReportService reports;private final ReportPdf pdf;private final ReportHwpService hwp;private final ObjectMapper json;
    public ReportArchiveService(ReportArchiveRepository repository,ReportService reports,ReportPdf pdf,ReportHwpService hwp,ObjectMapper json){this.repository=repository;this.reports=reports;this.pdf=pdf;this.hwp=hwp;this.json=json;}
    public Format parseFormat(String value){try{return Format.valueOf(value.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException ex){throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_REPORT_FORMAT","format은 PDF 또는 HWP여야 합니다.");}}
    public Archive createArchive(String id,boolean assessment,String classId,Format format,boolean raw) {
        var targets=repository.getTargets(id,assessment,classId);
        if(targets.size()>200)throw new ApiError.Failure(HttpStatus.PAYLOAD_TOO_LARGE,"REPORT_ARCHIVE_LIMIT","한 번에 최대200명까지 내보낼 수 있습니다. 반별로 나누어 요청해주세요.");
        Path path=null;
        try {
            path=Files.createTempFile("lit-report-archive-",".zip");var manifest=new ArrayList<Map<String,Object>>();var classNumbers=new LinkedHashMap<String,Integer>();int index=0;
            try(var zip=new ZipOutputStream(Files.newOutputStream(path),StandardCharsets.UTF_8)) {
                for(var target:targets) {
                    int number=++index;
                    if(!target.submitted()){manifest.add(Map.of("evaluationId",target.evaluationId(),"participantId",target.participantId(),"status","SKIPPED","code","SUBMISSION_REQUIRED"));continue;}
                    byte[] bytes;
                    try {var report=reports.teacherGenerate(target.evaluationId(),target.participantId());bytes=format==Format.PDF?pdf.render(report,raw):hwp.exportReport(report,raw);}
                    catch(ApiError.Failure failure){if(failure.status==HttpStatus.NOT_FOUND||failure.status==HttpStatus.FORBIDDEN||failure.status==HttpStatus.UNAUTHORIZED)throw failure;manifest.add(Map.of("evaluationId",target.evaluationId(),"participantId",target.participantId(),"status","FAILED","code",failure.code));continue;}
                    catch(RuntimeException failure){manifest.add(Map.of("evaluationId",target.evaluationId(),"participantId",target.participantId(),"status","FAILED","code","REPORT_EXPORT_FAILED"));continue;}
                    int classNumber=classNumbers.computeIfAbsent(target.evaluationId(),key->classNumbers.size()+1);
                    String entry=String.format(Locale.ROOT,"%03d-%s/%03d-%s.%s",classNumber,safeName(target.className()),number,safeName(target.nickname()),format.name().toLowerCase(Locale.ROOT));
                    zip.putNextEntry(new ZipEntry(entry));zip.write(bytes);zip.closeEntry();manifest.add(Map.of("evaluationId",target.evaluationId(),"participantId",target.participantId(),"status","DONE","file",entry));
                    if(Files.size(path)>100L*1024*1024)throw new ApiError.Failure(HttpStatus.PAYLOAD_TOO_LARGE,"REPORT_ARCHIVE_LIMIT","ZIP 크기가100MiB를 초과했습니다. 반별로 나누어 요청해주세요.");
                }
                zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(json.writeValueAsBytes(Map.of("format",format.name(),"includeConversations",raw,"total",manifest.size(),"succeeded",manifest.stream().filter(item->"DONE".equals(item.get("status"))).count(),"skipped",manifest.stream().filter(item->"SKIPPED".equals(item.get("status"))).count(),"failed",manifest.stream().filter(item->"FAILED".equals(item.get("status"))).count(),"items",manifest)));zip.closeEntry();
            }
            if(Files.size(path)>100L*1024*1024)throw new ApiError.Failure(HttpStatus.PAYLOAD_TOO_LARGE,"REPORT_ARCHIVE_LIMIT","ZIP 크기가100MiB를 초과했습니다.");
            // 생성 도중 공유 권한이 해제됐으면 다운로드를 시작하지 않습니다.
            repository.getTargets(id,assessment,classId);
            return new Archive(path,"lit-reports-"+format.name().toLowerCase(Locale.ROOT)+".zip");
        }catch(Exception failure){if(path!=null)try{Files.deleteIfExists(path);}catch(IOException cleanup){failure.addSuppressed(cleanup);}if(failure instanceof RuntimeException runtime)throw runtime;throw new IllegalStateException("보고서 ZIP을 생성할 수 없습니다.",failure);}
    }
    private String safeName(String value){String name=Objects.toString(value,"").replaceAll("[\\p{Cc}\\\\/:*?\"<>|.]","_").strip();return name.isBlank()?"report":name.substring(0,Math.min(name.length(),50));}
}
