package kr.lit.api;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.core.io.*;
import java.io.*;
import java.nio.file.Files;

@RestController
@RequestMapping("/api/teacher")
class ReportArchiveJobController {
    private final ReportArchiveJobService jobs;
    ReportArchiveJobController(ReportArchiveJobService jobs){this.jobs=jobs;}
    @PostMapping("/evaluations/{id}/report-archive-jobs") @ResponseStatus(HttpStatus.ACCEPTED)
    ReportArchiveJobService.View createClassJob(@PathVariable String id,@RequestParam(defaultValue="PDF") String format,@RequestParam(defaultValue="false") boolean includeConversations){return jobs.createJob(id,false,null,format,includeConversations);}
    @PostMapping({"/assessments/{id}/report-archive-jobs","/folders/{id}/report-archive-jobs"}) @ResponseStatus(HttpStatus.ACCEPTED)
    ReportArchiveJobService.View createFolderJob(@PathVariable String id,@RequestParam(defaultValue="PDF") String format,@RequestParam(defaultValue="false") boolean includeConversations){return jobs.createJob(id,true,null,format,includeConversations);}
    @GetMapping("/report-archive-jobs/{id}") ReportArchiveJobService.View getJob(@PathVariable String id){return jobs.getJob(id);}
    @GetMapping("/report-archive-jobs/{id}/download") ResponseEntity<Resource> downloadJob(@PathVariable String id)throws IOException{var archive=jobs.getDownload(id);return ResponseEntity.ok().contentLength(Files.size(archive.path())).contentType(MediaType.parseMediaType("application/zip")).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+archive.filename()+"\"").body(new InputStreamResource(Files.newInputStream(archive.path())));}
}
