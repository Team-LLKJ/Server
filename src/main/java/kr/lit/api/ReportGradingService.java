package kr.lit.api;

import org.springframework.stereotype.Service;
import java.util.*;
import static kr.lit.api.ReportModels.*;

/** 보고서 생성 시점의 실제 rubric-scores를 고정합니다. 과거 보고서에는 소급 반영하지 않습니다. */
@Service
public class ReportGradingService {
    private final ReportGradingRepository repository;
    public ReportGradingService(ReportGradingRepository repository){this.repository=repository;}
    @org.springframework.transaction.annotation.Transactional
    public GradingSnapshot getSnapshot(String id,String pid) {
        var source=repository.getSource(id,pid);if(!source.configured())return null;
        var criteria=source.criteria()==null?AssessmentService.defaultCriteria():source.criteria();
        var scores=new ArrayList<GradingCriterion>();
        for(var c:criteria) {
            var teacher=source.teachers().stream().filter(t->t.criterionId()==c.criterionId()).findFirst().orElse(null);
            var ai=source.analysis()==null||source.analysis().indicators()==null||c.indicator()==AssessmentModels.Indicator.MANUAL?null:source.analysis().indicators().stream().filter(i->i.indicator()==c.indicator()).findFirst().orElse(null);
            Integer level=teacher!=null?Integer.valueOf(teacher.level()):ai==null?null:ai.level();
            Double score=level==null?null:Math.round(c.maxScore()*level/4.0*100)/100.0;
            scores.add(new GradingCriterion(c.criterionId(),c.name(),c.maxScore(),score,teacher!=null,teacher==null?null:teacher.feedback(),ai==null?null:ai.reason()));
        }
        return new GradingSnapshot(source.status(),List.copyOf(scores));
    }
}
