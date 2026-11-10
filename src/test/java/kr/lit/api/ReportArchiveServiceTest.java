package kr.lit.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportArchiveServiceTest {
    @Test void failedStudentDoesNotDiscardOtherReportsAndNamesCannotEscapeArchive()throws Exception {
        var repository=mock(ReportArchiveRepository.class);var reports=mock(ReportService.class);var hwp=mock(ReportHwpService.class);var json=new ObjectMapper();
        var service=new ReportArchiveService(repository,reports,mock(ReportPdf.class),hwp,json);
        var targets=List.of(new ReportArchiveRepository.Target("e","bad","../반","같은 학생",true),new ReportArchiveRepository.Target("e","good","../반","같은 학생",true),new ReportArchiveRepository.Target("e","other","../반","같은 학생",true),new ReportArchiveRepository.Target("e","none","../반","미제출",false));
        when(repository.getTargets("e",false,null)).thenReturn(targets);when(reports.teacherGenerate("e","bad")).thenThrow(new IllegalStateException("private error"));
        var report=new ReportModels.UsageReport("r",1,java.time.Instant.EPOCH,null);when(reports.teacherGenerate("e","good")).thenReturn(report);when(reports.teacherGenerate("e","other")).thenReturn(report);when(hwp.exportReport(report,false)).thenReturn(new byte[]{1,2,3});
        var archive=service.createArchive("e",false,null,ReportArchiveService.Format.HWP,false);
        try(var zip=new ZipFile(archive.path().toFile())){var manifest=json.readTree(zip.getInputStream(zip.getEntry("manifest.json")));assertThat(manifest.path("succeeded").asInt()).isEqualTo(2);assertThat(manifest.path("failed").asInt()).isEqualTo(1);assertThat(manifest.path("skipped").asInt()).isEqualTo(1);assertThat(manifest.toString()).doesNotContain("private error");assertThat(zip.stream().map(ZipEntry::getName)).noneMatch(n->n.contains(".."));assertThat(zip.size()).isEqualTo(3);}
        finally{Files.deleteIfExists(archive.path());}
        verify(reports,never()).teacherGenerate("e","none");
    }
    @Test void archiveLimitRejectsBeforeGeneratingAnyStudentReport() {
        var repository=mock(ReportArchiveRepository.class);var reports=mock(ReportService.class);var service=new ReportArchiveService(repository,reports,mock(ReportPdf.class),mock(ReportHwpService.class),new ObjectMapper());
        when(repository.getTargets("e",false,null)).thenReturn(Collections.nCopies(201,new ReportArchiveRepository.Target("e","p","반","학생",true)));
        assertThatThrownBy(()->service.createArchive("e",false,null,ReportArchiveService.Format.PDF,false)).isInstanceOfSatisfying(ApiError.Failure.class,f->assertThat(f.code).isEqualTo("REPORT_ARCHIVE_LIMIT"));verifyNoInteractions(reports);
    }
}
