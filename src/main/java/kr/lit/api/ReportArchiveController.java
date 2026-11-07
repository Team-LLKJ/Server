package kr.lit.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.*;
import java.io.*;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/teacher")
class ReportArchiveController {
    private final ReportArchiveService archives;
    ReportArchiveController(ReportArchiveService archives){this.archives=archives;}
    @GetMapping("/evaluations/{id}/reports/archive")
    ResponseEntity<Resource> exportClass(@PathVariable String id,@RequestParam(defaultValue="PDF") String format,@RequestParam(defaultValue="false") boolean includeConversations){return download(archives.createArchive(id,false,null,archives.parseFormat(format),includeConversations));}
    @GetMapping({"/assessments/{id}/reports/archive","/folders/{id}/reports/archive"})
    ResponseEntity<Resource> exportFolder(@PathVariable String id,@RequestParam(defaultValue="PDF") String format,@RequestParam(defaultValue="false") boolean includeConversations){return download(archives.createArchive(id,true,null,archives.parseFormat(format),includeConversations));}
    @GetMapping("/assessments/{id}/classes/{classId}/reports/archive")
    ResponseEntity<Resource> exportAssessmentClass(@PathVariable String id,@PathVariable String classId,@RequestParam(defaultValue="PDF") String format,@RequestParam(defaultValue="false") boolean includeConversations){return download(archives.createArchive(id,true,classId,archives.parseFormat(format),includeConversations));}
    private ResponseEntity<Resource> download(ReportArchiveService.Archive archive){
        try {
            long size=Files.size(archive.path());
            var input=new FilterInputStream(Files.newInputStream(archive.path())){@Override public void close()throws IOException{try{super.close();}finally{Files.deleteIfExists(archive.path());}}};
            return ResponseEntity.ok().contentLength(size).contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+archive.filename()+"\"").body(new InputStreamResource(input));
        }catch(IOException failure){try{Files.deleteIfExists(archive.path());}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw new IllegalStateException("ZIP을 전송할 수 없습니다.",failure);}
    }
}
