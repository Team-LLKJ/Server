package kr.lit.api;

import jakarta.validation.constraints.Min;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static kr.lit.api.ReportModels.*;

@RestController
@RequestMapping("/api")
@Validated
class ReportHwpController {
    private static final String HWP_MEDIA_TYPE="application/x-hwp";
    private final ReportService reports;
    private final ReportHwpService hwp;
    ReportHwpController(ReportService reports,ReportHwpService hwp){this.reports=reports;this.hwp=hwp;}
    @GetMapping(value={"/teacher/evaluations/{id}/students/{participantId}/report/hwp","/teacher/evaluations/{id}/students/{participantId}/report.hwp","/teacher/evaluations/{id}/participants/{participantId}/report/hwp","/teacher/evaluations/{id}/participants/{participantId}/report.hwp"},produces=HWP_MEDIA_TYPE)
    ResponseEntity<byte[]> exportTeacherReport(@PathVariable String id,@PathVariable String participantId,
            @RequestParam(required=false) @Min(1) Integer version,@RequestParam(defaultValue="false") boolean includeConversations) {
        return downloadReport(reports.teacherHwpReport(id,participantId,version),includeConversations);
    }
    private ResponseEntity<byte[]> downloadReport(UsageReport report,boolean includeConversations) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(HWP_MEDIA_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("lit-report-"+report.id()+"-v"+report.version()+".hwp").build().toString())
                .body(hwp.exportReport(report,includeConversations));
    }
}
