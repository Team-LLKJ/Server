package kr.lit.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static kr.lit.api.ReportModels.*;

@RestController
@RequestMapping("/api")
@Validated
class ReportController {
 private final ReportService reports;private final ReportPdf pdf;
 ReportController(ReportService reports,ReportPdf pdf){this.reports=reports;this.pdf=pdf;}
 @GetMapping("/teacher/evaluations/{id}/students")
 Students students(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return reports.students(id,page,size);}
 @GetMapping("/teacher/evaluations/{id}/students/{participantId}")
 Detail detail(@PathVariable String id,@PathVariable String participantId){return reports.detail(id,participantId);}
 @PutMapping("/teacher/evaluations/{id}/students/{participantId}/review")
 TeacherReview review(@PathVariable String id,@PathVariable String participantId,@Valid @RequestBody ReviewInput input){return reports.saveReview(id,participantId,input);}
 @PostMapping("/teacher/evaluations/{id}/students/{participantId}/reports")
 UsageReport generate(@PathVariable String id,@PathVariable String participantId){return reports.teacherGenerate(id,participantId);}
 @GetMapping("/teacher/evaluations/{id}/students/{participantId}/reports")
 Object versions(@PathVariable String id,@PathVariable String participantId){return reports.versions(id,participantId);}
 @GetMapping("/teacher/evaluations/{id}/students/{participantId}/report")
 UsageReport report(@PathVariable String id,@PathVariable String participantId,@RequestParam(required=false) @Min(1) Integer version){return reports.teacherReport(id,participantId,version);}
 @GetMapping(value="/teacher/evaluations/{id}/students/{participantId}/report/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
 ResponseEntity<byte[]> pdf(@PathVariable String id,@PathVariable String participantId,@RequestParam(required=false) @Min(1) Integer version,
                           @RequestParam(defaultValue="false") boolean includeConversations){return download(reports.teacherReport(id,participantId,version),includeConversations);}
 @PostMapping("/evaluations/{id}/reports")
 UsageReport studentGenerate(@PathVariable String id,@RequestHeader("X-Participant-Token") String token){return reports.studentGenerate(id,token);}
 @GetMapping("/evaluations/{id}/report")
 UsageReport studentReport(@PathVariable String id,@RequestHeader("X-Participant-Token") String token,@RequestParam(required=false) @Min(1) Integer version){return reports.studentReport(id,token,version);}
 @GetMapping(value="/evaluations/{id}/report/pdf",produces=MediaType.APPLICATION_PDF_VALUE)
 ResponseEntity<byte[]> studentPdf(@PathVariable String id,@RequestHeader("X-Participant-Token") String token,@RequestParam(required=false) @Min(1) Integer version,
                           @RequestParam(defaultValue="false") boolean includeConversations){return download(reports.studentReport(id,token,version),includeConversations);}
 private ResponseEntity<byte[]> download(UsageReport report,boolean raw){return ResponseEntity.ok()
   .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"lit-report-"+report.id()+"-v"+report.version()+".pdf\"")
   .contentType(MediaType.APPLICATION_PDF).body(pdf.render(report,raw));}
}
