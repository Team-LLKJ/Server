package kr.lit.api;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProcessGradingWorkerTest {
    @Test void dispatchesAnotherStudentAndChecksRecoveryWhileJobsAreBusy() throws Exception {
        var repository=mock(ProcessGradingRepository.class);
        var worker=new ProcessGradingWorker(repository,mock(ProcessGradingAiClient.class),
                mock(ProcessGradingEngine.class),mock(ProcessRubricGrader.class),mock(AutomaticDisclosureService.class));
        var first=new ProcessGradingRepository.PendingJob("first","student-1",Instant.EPOCH);
        var second=new ProcessGradingRepository.PendingJob("second","student-2",Instant.EPOCH);
        when(repository.getPendingJobRows()).thenReturn(List.of(first,second));
        when(repository.claimJob(anyString())).thenReturn(true);
        var started=new CountDownLatch(2);
        var release=new CountDownLatch(1);
        when(repository.getInput(anyString())).thenAnswer(invocation->{
            started.countDown();
            release.await(5,TimeUnit.SECONDS);
            throw new IllegalStateException("End test job");
        });
        try {
            worker.analyzePending();
            assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            worker.analyzePending();
            verify(repository,times(2)).recoverJobs();
            verify(repository).claimJob(first.id());
            verify(repository).claimJob(second.id());
        } finally {
            release.countDown();
            worker.stopWorkers();
        }
    }
}
