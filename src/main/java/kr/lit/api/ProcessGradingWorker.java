package kr.lit.api;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static kr.lit.api.ProcessGradingModels.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ProcessGradingWorker {
    private final ProcessGradingRepository repository;private final ProcessGradingAiClient ai;private final ProcessGradingEngine engine;private final ProcessRubricGrader rubric;private final AutomaticDisclosureService disclosures;
    public ProcessGradingWorker(ProcessGradingRepository repository,ProcessGradingAiClient ai,ProcessGradingEngine engine,ProcessRubricGrader rubric,AutomaticDisclosureService disclosures){this.repository=repository;this.ai=ai;this.engine=engine;this.rubric=rubric;this.disclosures=disclosures;}
    /**
     * 대기 작업을 처리합니다. LLM 루브릭 판정(rubric 키)이 켜져 있으면 질문 용도·답안 기여도 분류가 끝난 학생부터(최대 10분 대기) 학생 단위로 판정합니다.
     * 다시 제출하면 이전 대기 작업은 SUPERSEDED가 되어 마지막 제출본만 채점합니다(ProcessGradingRepository.insertJob).
     */
    @Scheduled(fixedDelayString="${lit.grading.process-interval-ms:3000}",initialDelayString="${lit.grading.process-interval-ms:3000}")
    public void analyzePending() {
        repository.recoverJobs();
        for(var pending:repository.getPendingJobRows()) {
            if(rubric.isActive()&&!rubric.ready(pending.participantId(),pending.createdAt())) continue;
            processJob(pending.id());
        }
    }
    /**
     * 재분석해도 교사가 고친 항목은 덮어쓰지 않습니다. 같은 학생의 바로 앞 분석에 교사 검토가 있으면 그 보정(overrides)을 새 결과에 다시 적용한
     * 검토 초안(확정 안 됨)을 저장합니다. 문항·항목 구조가 달라 적용할 수 없으면 건너뜁니다. 확정은 교사가 새 결과를 보고 다시 합니다.
     */
    void carryOverReview(String job,Input input,Result result) {
        try {
            var jobs=repository.getJobs(input.evaluationId(),input.participantId());
            int index=-1;for(int i=0;i<jobs.size();i++) if(jobs.get(i).id().equals(job)) index=i;
            if(index<0||repository.getReview(job).isPresent()) return;
            for(int i=index+1;i<jobs.size();i++) {
                var previous=repository.getReview(jobs.get(i).id());
                if(previous.isEmpty()) continue;
                var old=previous.get();
                if(old.overrides()==null||old.overrides().isEmpty()) return;
                var applied=engine.applyReview(input,result,old.overrides());
                repository.saveReview(job,new Review(1,old.teacherId(),java.time.Instant.now(),old.overrides(),old.complianceDecision(),old.complianceReason(),applied,false));
                return;
            }
        } catch(RuntimeException ignored) {} // 구조가 달라 적용할 수 없는 보정은 옮기지 않습니다.
    }
    public void processJob(String job) {
        if(!repository.claimJob(job))return;
        try {
            var input=repository.getInput(job);Result result;
            disclosures.getDisclosure(input.evaluationId(),input.participantId());
            if(rubric.isActive()){var graded=gradeWithJudge(job,input);repository.completeJob(job,rubric.modelLabel(),graded);carryOverReview(job,input,graded);return;}
            if(input.policy()==null)result=engine.hold(input,"PRE_EVALUATION_POLICY_REQUIRED");
            else if(engine.isV3(input)&&input.policy().excludedCriteria().containsAll(Criterion.active()))result=engine.notApplicable(input,"PRESELECTED_NOT_APPLICABLE");
            else if(input.policy().questionWeights().size()!=input.questionIds().size()
                    ||!input.policy().questionWeights().stream().map(QuestionWeight::questionId).collect(java.util.stream.Collectors.toSet()).equals(new java.util.HashSet<>(input.questionIds())))result=engine.hold(input,"QUESTION_WEIGHTS_OUTDATED");
            else if(repository.encode(input).getBytes(StandardCharsets.UTF_8).length>524288)result=engine.hold(input,"INPUT_TOO_LARGE_NO_TRUNCATION");
            else {
                boolean observedAi=input.sources().stream().anyMatch(s->s.kind().equals("STUDENT_QUESTION")||s.kind().equals("DISCLOSURE")&&!s.text().isBlank());
                if(!observedAi&&input.policy().noAiPolicy()!=NoAiPolicy.USE_RECORDED_EVIDENCE) {
                    var held=engine.hold(input,"NO_OBSERVED_AI_EVIDENCE");
                    result=input.policy().noAiPolicy()==NoAiPolicy.NOT_APPLICABLE?engine.notApplicable(input,"교사 사전 정책에 따라 평가 대상이 아닙니다. 관측되지 않은 외부 AI 미사용을 단정하지 않습니다."):held;
                }else result=engine.evaluate(input,recommendBatches(job,input));
            }
            repository.completeJob(job,ai.getModel(),result);
        }catch(ProcessGradingAiClient.GradingFailure ex){repository.failJob(job,ex.code);}
        catch(SemanticJudgeClient.JudgeFailure ex){repository.failJob(job,ex.code());}
        catch(Exception ex){repository.failJob(job,"GRADING_VALIDATION_FAILED");}
    }
    /**
     * LLM 루브릭 판정(학생 단위, 문항 가중치 없음). 사전 정책은 없어도 됩니다: 정책의 제외 항목은 NA로 둡니다.
     * AI 미사용 학생은 문서 규칙(자기 주도적 사고는 모델 없이 교사 확인 HOLD, 나머지 NA)을 따릅니다. 이 규칙이 noAiPolicy=NOT_APPLICABLE에 해당합니다.
     */
    private Result gradeWithJudge(String job,Input input) {
        var policy=input.policy();
        if(policy!=null&&policy.excludedCriteria().containsAll(Criterion.active()))return engine.notApplicable(input,"PRESELECTED_NOT_APPLICABLE");
        if(repository.encode(input).getBytes(StandardCharsets.UTF_8).length>524288)return engine.hold(input,"INPUT_TOO_LARGE_NO_TRUNCATION");
        return rubric.grade(input,()->repository.touchJob(job));
    }
    private AiOutput recommendBatches(String job,Input input) {
        var recommendations=new java.util.ArrayList<Recommendation>();Compliance compliance=null;String summary=null;
        for(int start=0;start<input.questionIds().size();start+=4) {
            var ids=input.questionIds().subList(start,Math.min(start+4,input.questionIds().size()));
            // 첫 묶음은 전체 기록으로 공통 투명성·규정 준수를 평가하고, 이후 묶음은 문항 근거만 보냅니다.
            var sources=start==0?input.sources():input.sources().stream().filter(s->GLOBAL.equals(s.questionId())||ids.contains(s.questionId())).toList();
            var batch=new Input(input.evaluationId(),input.participantId(),input.submissionHash(),input.title(),input.aiPolicy(),input.policy(),ids,sources,input.limitations(),input.analysisScope(),input.inputPaths(),input.usageAnalysis());
            repository.touchJob(job);var output=ai.recommendLevels(batch);repository.touchJob(job);
            if(output==null||output.recommendations()==null)throw new IllegalArgumentException("INVALID_AI_OUTPUT");
            // 묶음 자체도 검증하여 누락·중복·잘못된 문항을 최종 병합에서 숨기지 않습니다.
            engine.evaluate(batch,output);
            if(start==0){compliance=output.compliance();summary=output.summary();}
            for(var recommendation:output.recommendations())if(start==0||recommendation.criterion()!=Criterion.TRANSPARENCY)recommendations.add(recommendation);
        }
        return new AiOutput(compliance,recommendations,summary);
    }

}
