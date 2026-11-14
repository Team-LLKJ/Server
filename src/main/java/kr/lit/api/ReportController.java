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
 Students students(@PathVariable String id,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="") String query){return reports.students(id,page,size,query);}
 @GetMapping("/teacher/evaluations/{id}/students/{participantId}")
 Detail detail(@PathVariable String id,@PathVariable String participantId){return reports.detail(id,participantId);}
 @PutMapping("/teacher/evaluations/{id}/students/{participantId}/review")
 TeacherReview review(@PathVariable String id,@PathVariable String participantId,@Valid @RequestBody ReviewInput input){return reports.saveReview(id,participantId,input);}
 @GetMapping({"/teacher/evaluations/{id}/students/{participantId}/ai-disclosure","/teacher/evaluations/{id}/participants/{participantId}/ai-disclosure"})
 Object disclosure(@PathVariable String id,@PathVariable String participantId){return reports.teacherDisclosure(id,participantId);}
 @PostMapping("/teacher/evaluations/{id}/students/{participantId}/reports")
 UsageReport generate(@PathVariable String id,@PathVariable String participantId){return reports.teacherGenerate(id,participantId);}
 @GetMapping("/teacher/evaluations/{id}/students/{participantId}/reports")
 Object versions(@PathVariable String id,@PathVariable String participantId){return reports.versions(id,participantId);}
 @GetMapping("/teacher/evaluations/{id}/students/{participantId}/report")
 UsageReport report(@PathVariable String id,@PathVariable String participantId,@RequestParam(required=false) @Min(1) Integer version){return reports.teacherReport(id,participantId,version);}
 @GetMapping(value={"/teacher/evaluations/{id}/students/{participantId}/report/pdf","/teacher/evaluations/{id}/participants/{participantId}/report/pdf"},produces=MediaType.APPLICATION_PDF_VALUE)
 ResponseEntity<byte[]> pdf(@PathVariable String id,@PathVariable String participantId,@RequestParam(required=false) @Min(1) Integer version,
                           @RequestParam(defaultValue="false") boolean includeConversations){return download(reports.teacherReport(id,participantId,version),includeConversations);}
 @GetMapping(value={"/teacher/evaluations/{id}/students/{participantId}/report.pdf","/teacher/evaluations/{id}/participants/{participantId}/report.pdf"},produces=MediaType.APPLICATION_PDF_VALUE)
 ResponseEntity<byte[]> gradingPdf(@PathVariable String id,@PathVariable String participantId,@RequestParam(required=false) @Min(1) Integer version,
                           @RequestParam(defaultValue="false") boolean includeConversations){return download(version==null?reports.teacherGenerate(id,participantId):reports.teacherReport(id,participantId,version),includeConversations);}
 // 학생용 보고서 API(/api/evaluations/{id}/reports, /report, /report/pdf)는 없앴습니다(2026-11-14). 보고서는 교사 API로만 만듭니다.
 private ResponseEntity<byte[]> download(UsageReport report,boolean raw){return ResponseEntity.ok()
   .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"lit-report-"+report.id()+"-v"+report.version()+".pdf\"")
   .contentType(MediaType.APPLICATION_PDF).body(pdf.render(report,raw));}
}
