package kr.lit.api;

import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

/** 사용 통계와 근거 기반 역량 평가를 분리해 교사 분석 화면에 제공합니다. */
@Service
public class ProcessAnalysisService {
    private final ProcessGradingService grading;
    private final ProcessGradingRepository repository;
    private final AssessmentService assessments;
    public ProcessAnalysisService(ProcessGradingService grading,ProcessGradingRepository repository,AssessmentService assessments){this.grading=grading;this.repository=repository;this.assessments=assessments;}
    public record CriterionSummary(Criterion criterion,String name,int maxScore,Integer level,String label,BigDecimal score,String status,List<Cell> cells) {}
    public record AnalysisView(String participantId,String rubricVersion,String runId,String status,boolean confirmed,
            BigDecimal total,Compliance compliance,List<CriterionSummary> criteria,CriterionSummary studentContribution,
            List<AssessmentModels.Usage> aiUsage,AssessmentModels.Contribution textSimilarity,List<String> limitations,String summary,PartialScore partialScore,AnalysisScope analysisScope,InputPathAnalyzer.View inputPaths,PurposeUsageAnalyzer.View purposeUsage,Result aiRecommendation,Review teacherReview,boolean matchesCurrentSubmission) {
        public AnalysisView(String pid,String version,String run,String status,boolean confirmed,BigDecimal total,Compliance compliance,List<CriterionSummary> criteria,CriterionSummary contribution,List<AssessmentModels.Usage> usage,AssessmentModels.Contribution similarity,List<String> limits){this(pid,version,run,status,confirmed,total,compliance,criteria,contribution,usage,similarity,limits,null,null,null,null,null,null,null,false);}
    }
    public AnalysisView getAnalysis(String id,String pid) {
        var jobs=grading.getRuns(id,pid); // 평가·참가자 소유권 검증을 먼저 수행합니다.
        var usage=assessments.analysis(id,pid);
        var limits=new ArrayList<String>();limits.add("문장 유사도 비중은 실제 작성자 비율이나 역량 점수가 아닙니다.");
        if(jobs.isEmpty())return new AnalysisView(pid,RUBRIC_VERSION,null,"NOT_ANALYZED",false,null,null,List.of(),null,usage.usage(),usage.contribution(),limits);
        var job=jobs.get(0);var review=repository.getReview(job.id()).orElse(null);
        Result result=review==null?job.result():review.result();
        if(result==null){var frozen=repository.getInput(job.id());return new AnalysisView(pid,job.rubricVersion(),job.id(),job.status(),false,null,null,List.of(),null,List.of(),usage.contribution(),limits,null,null,frozen.analysisScope(),frozen.inputPaths(),frozen.usageAnalysis(),null,review,repository.getCurrentSubmissionHash(id,pid).equals(frozen.submissionHash()));}
        var input=repository.getInput(job.id());var summaries=new ArrayList<CriterionSummary>();
        for(var criterion:Criterion.values()) {
            var cells=result.cells().stream().filter(c->c.criterion()==criterion).toList();
            BigDecimal score=BigDecimal.ZERO;boolean complete=!cells.isEmpty();
            for(var cell:cells) {
                if(cell.score()==null){complete=false;continue;}
                if(criterion==Criterion.TRANSPARENCY)score=score.add(cell.score());
                else {
                    var weight=input.policy()==null?null:input.policy().questionWeights().stream().filter(w->w.questionId().equals(cell.questionId())).findFirst().orElse(null);
                    if(weight==null)complete=false;else score=score.add(cell.score().multiply(weight.weight()).movePointLeft(2));
                }
            }
            // 서로 다른 문항 단계를 평균 내어 가상의 단계를 만들지 않습니다.
            var levels=cells.stream().map(Cell::level).distinct().toList();
            boolean notApplicable=!cells.isEmpty()&&cells.stream().allMatch(c->c.status().equals("NOT_APPLICABLE"));
            Integer level=complete&&levels.size()==1?levels.get(0):null;
            boolean directScore=cells.stream().anyMatch(c->c.level()==null&&c.score()!=null&&c.status().equals("TEACHER_REVIEWED"));
            String label=notApplicable?"평가 대상 아님":level==null?(complete?(directScore?"교사 점수 입력":"문항별 단계 상이"):"판단 보류"):labelLevel(level,job.rubricVersion());
            summaries.add(new CriterionSummary(criterion,criterion.displayName(),criterion.maxScore(),level,label,complete?score.setScale(2,RoundingMode.HALF_UP):null,notApplicable?"NOT_APPLICABLE":complete?"DONE":"HOLD",cells));
        }
        limits.addAll(result.limitations());
        return new AnalysisView(pid,job.rubricVersion(),job.id(),result.status(),review!=null&&review.confirmed(),result.total(),result.compliance(),List.copyOf(summaries),summaries.stream().filter(c->c.criterion()==Criterion.CONTRIBUTION).findFirst().orElse(null),RUBRIC_VERSION.equals(job.rubricVersion())?List.of():usage.usage(),usage.contribution(),List.copyOf(limits),result.summary(),result.partialScore(),input.analysisScope(),input.inputPaths(),input.usageAnalysis(),job.result(),review,repository.getCurrentSubmissionHash(id,pid).equals(input.submissionHash()));
    }
    private String labelLevel(int level,String version){return List.of("매우 못함","못함","보통","잘함","매우 잘함").get(level-(LEGACY_RUBRIC_VERSION.equals(version)?0:1));}
}
