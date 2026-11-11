package kr.lit.api;

import org.springframework.stereotype.Service;
import java.util.*;
import static kr.lit.api.ReportModels.*;

/** 보고서 생성 시점의 실제 rubric-scores를 고정합니다. 과거 보고서에는 소급 반영하지 않습니다. */
@Service
public class ReportGradingService {
    private final ReportGradingRepository repository;
    private final ProcessGradingRepository process;
    public ReportGradingService(ReportGradingRepository repository,ProcessGradingRepository process){this.repository=repository;this.process=process;}
    @org.springframework.transaction.annotation.Transactional
    public GradingSnapshot getSnapshot(String id,String pid) {
        var jobs=process.getJobs(id,pid);
        if(!jobs.isEmpty()&&ProcessGradingModels.RUBRIC_VERSION.equals(jobs.get(0).rubricVersion())){
            var job=jobs.get(0);var input=process.getInput(job.id());
            if(job.result()!=null&&ProcessGradingEngine.studentLevel(job.result().cells()))return getStudentSnapshot(job,input);
            if(input.policy()!=null)return getProcessSnapshot(job,input);
        }
        var source=repository.getSource(id,pid);if(!source.configured())return null;
        var criteria=source.criteria()==null?AssessmentService.defaultCriteria():source.criteria();
        var scores=new ArrayList<GradingCriterion>();
        for(var c:criteria) {
            var teacher=source.teachers().stream().filter(t->t.criterionId()==c.criterionId()).findFirst().orElse(null);
            var ai=source.analysis()==null||source.analysis().indicators()==null||c.indicator()==AssessmentModels.Indicator.MANUAL?null:source.analysis().indicators().stream().filter(i->i.indicator()==c.indicator()).findFirst().orElse(null);
            Integer level=teacher!=null?Integer.valueOf(teacher.level()):ai==null?null:ai.level();
            Double score=level==null?null:RubricJudge.score(c.maxScore(),level+1).doubleValue();
            scores.add(new GradingCriterion(c.criterionId(),c.name(),c.maxScore(),score,teacher!=null,teacher==null?null:teacher.feedback(),ai==null?null:ai.reason()));
        }
        return new GradingSnapshot(source.status(),List.copyOf(scores));
    }
    /**
     * 학생 단위 루브릭(LLM 판정) 결과. 학생에게 나가는 PDF·HWP에는 교사가 확정한 결과만 씁니다.
     * 확정 전에는 점수·AI 추천을 넣지 않고 "채점 중"으로 둡니다. 확정 후 총점은 NA 재분배 100점 만점(partialScore)입니다.
     */
    private GradingSnapshot getStudentSnapshot(ProcessGradingModels.Job job,ProcessGradingModels.Input input){
        var review=process.getReview(job.id()).filter(ProcessGradingModels.Review::confirmed).orElse(null);
        var criteria=new ArrayList<GradingCriterion>();
        var cells=review==null?List.<ProcessGradingModels.Cell>of():review.result().cells();
        for(var criterion:ProcessGradingModels.Criterion.values()){
            var cell=cells.stream().filter(c->c.criterion()==criterion).findFirst().orElse(null);
            if(cell==null){criteria.add(new GradingCriterion(criterion.ordinal()+1,criterion.displayName(),criterion.maxScore(),null,false,null,null));continue;}
            boolean na=cell.status().equals("NOT_APPLICABLE");
            String feedback=String.join(" ",review.overrides().stream().filter(o->o.criterion()==criterion).map(ProcessGradingModels.LevelOverride::reason).toList());
            // 재분배 후 배점(정수)과 그 기준 점수. NA 칸은 원래 배점을 보여 주고 "해당 없음"으로 표시합니다.
            int max=na||cell.adjustedMaxScore()==null?criterion.maxScore():cell.adjustedMaxScore();
            criteria.add(new GradingCriterion(criterion.ordinal()+1,criterion.displayName(),max,cell.score()==null?null:cell.score().doubleValue(),true,
                    feedback.isBlank()?null:feedback,na?"해당 없음: 배점은 나머지 항목에 나눠 계산했습니다.":cell.reason(),na));
        }
        return new GradingSnapshot(review==null?"DRAFT":"CONFIRMED",List.copyOf(criteria),job.rubricVersion(),review==null?null:review.result().summary(),
                review==null?null:job.result(),review,input.inputPaths(),input.usageAnalysis(),input.analysisScope());
    }
    private GradingSnapshot getProcessSnapshot(ProcessGradingModels.Job job,ProcessGradingModels.Input input){
        var review=process.getReview(job.id()).orElse(null);var result=review==null?job.result():review.result();
        var criteria=new ArrayList<GradingCriterion>();
        for(var criterion:ProcessGradingModels.Criterion.values()){
            var cells=result==null?List.<ProcessGradingModels.Cell>of():result.cells().stream().filter(c->c.criterion()==criterion).toList();
            java.math.BigDecimal score=java.math.BigDecimal.ZERO;boolean complete=!cells.isEmpty();
            for(var cell:cells){
                if(cell.score()==null){complete=false;continue;}
                if(criterion==ProcessGradingModels.Criterion.TRANSPARENCY)score=score.add(cell.score());
                else{var weight=input.policy().questionWeights().stream().filter(w->w.questionId().equals(cell.questionId())).findFirst().orElse(null);if(weight==null)complete=false;else score=score.add(cell.score().multiply(weight.weight()).movePointLeft(2));}
            }
            String feedback=review==null?null:String.join(" ",review.overrides().stream().filter(o->o.criterion()==criterion).map(ProcessGradingModels.LevelOverride::reason).toList());
            String reason=String.join(" ",cells.stream().map(ProcessGradingModels.Cell::reason).toList());
            boolean teacher=review!=null&&(review.confirmed()||!cells.isEmpty()&&cells.stream().allMatch(c->c.status().equals("TEACHER_REVIEWED")));
            criteria.add(new GradingCriterion(criterion.ordinal()+1,criterion.displayName(),criterion.maxScore(),complete?score.setScale(2,java.math.RoundingMode.HALF_UP).doubleValue():null,teacher,feedback,reason));
        }
        return new GradingSnapshot(result==null?job.status():review!=null?review.confirmed()?"CONFIRMED":"DRAFT":result.status(),List.copyOf(criteria),job.rubricVersion(),result==null?null:result.summary(),job.result(),review,input.inputPaths(),input.usageAnalysis(),input.analysisScope());
    }

}
