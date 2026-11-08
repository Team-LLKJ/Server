package kr.lit.api;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static kr.lit.api.ProcessGradingModels.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ProcessGradingWorker {
    private final ProcessGradingRepository repository;private final ProcessGradingAiClient ai;private final ProcessGradingEngine engine;
    public ProcessGradingWorker(ProcessGradingRepository repository,ProcessGradingAiClient ai,ProcessGradingEngine engine){this.repository=repository;this.ai=ai;this.engine=engine;}
    @Scheduled(fixedDelayString="${lit.grading.process-interval-ms:3000}",initialDelayString="${lit.grading.process-interval-ms:3000}")
    public void analyzePending() {
        repository.recoverJobs();for(String job:repository.getPendingJobs())processJob(job);
    }
    public void processJob(String job) {
        if(!repository.claimJob(job))return;
        try {
            var input=repository.getInput(job);Result result;
            if(input.policy()==null)result=engine.hold(input,"PRE_EVALUATION_POLICY_REQUIRED");
            else if(engine.isV3(input)&&input.policy().excludedCriteria().size()==Criterion.values().length)result=engine.notApplicable(input,"PRESELECTED_NOT_APPLICABLE");
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
        catch(Exception ex){repository.failJob(job,"GRADING_VALIDATION_FAILED");}
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
