package kr.lit.api;

import org.springframework.stereotype.Component;
import java.math.*;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

/** 근거 인용·충족 조건 검증 및 점수 계산. 외부 AI·DB·시계에 접근하지 않습니다. */
@Component
public class ProcessGradingEngine {
    public boolean isV3(Input input){return input.policy()!=null&&RUBRIC_VERSION.equals(input.policy().rubricVersion());}
    private boolean excluded(Input input,Criterion c){return isV3(input)&&input.policy().excludedCriteria().contains(c);}
    public boolean isLegacy(Input input){return input.policy()!=null&&LEGACY_RUBRIC_VERSION.equals(input.policy().rubricVersion());}
    private int minimumLevel(Input input){return isLegacy(input)?0:1;}
    private int maximumLevel(Input input){return isLegacy(input)?4:5;}
    public List<String> requiredConditions(Criterion criterion,int level) {return requiredConditions(criterion,level,false);}
    public List<String> requiredConditions(Criterion criterion,int level,boolean legacy) {
        var list=new ArrayList<String>();list.add(criterion.name()+"_L"+level);
        if(level==(legacy?4:5))list.add(criterion.name()+"_FINAL_APPLICATION");
        return List.copyOf(list);
    }
    public Result evaluate(Input input,AiOutput output) {
        if(output==null||output.recommendations()==null||output.compliance()==null)throw new IllegalArgumentException("INVALID_AI_OUTPUT");
        var expected=keys(input);var recommended=new HashMap<String,Recommendation>();
        for(var r:output.recommendations())if(r==null||r.criterion()==null||!expected.contains(key(r.questionId(),r.criterion()))||recommended.put(key(r.questionId(),r.criterion()),r)!=null)
            throw new IllegalArgumentException("INVALID_AI_OUTPUT");
        if(recommended.size()!=expected.size())throw new IllegalArgumentException("INCOMPLETE_AI_OUTPUT");
        var cells=new ArrayList<Cell>();
        for(String q:input.questionIds())for(var c:Criterion.values())if(c!=Criterion.TRANSPARENCY)cells.add(excluded(input,c)?notApplicable(q,c):validate(input,recommended.get(key(q,c))));
        cells.add(excluded(input,Criterion.TRANSPARENCY)?notApplicable(GLOBAL,Criterion.TRANSPARENCY):validate(input,recommended.get(key(GLOBAL,Criterion.TRANSPARENCY))));
        var compliance=output.compliance();
        if(!Set.of("OK","CHECK","INSUFFICIENT_EVIDENCE").contains(Objects.toString(compliance.status(),""))||!validCitations(input,GLOBAL,compliance.evidence())
                ||compliance.reason()==null||compliance.reason().isBlank()||(!"INSUFFICIENT_EVIDENCE".equals(compliance.status())&&compliance.evidence().isEmpty()))
            compliance=new Compliance("INSUFFICIENT_EVIDENCE","규정 준수 근거를 검증할 수 없습니다.",List.of(),List.of("위반 확정은 교사가 판단합니다."));
        if(isV3(input)&&!GradingEvidenceValidator.validNarrative(output.summary(),4,6))return hold(input,"INVALID_SUMMARY_NARRATIVE");
        return calculate(input,cells,compliance,List.of("LIT 제안 척도·교사 확인 필요. 인용문 검증은 의미 판단의 정확성을 보장하지 않습니다.","교과 지식 정확성·경고 횟수·유사도를 자동 감점하지 않습니다."),output.summary());
    }
    private Cell validate(Input input,Recommendation r) {
        if(r.level()==null) {
            var safeConditions=r.conditions()==null?List.<Condition>of():r.conditions().stream().filter(c->c!=null&&c.key()!=null&&validCitations(input,r.questionId(),c.evidence())).toList();
            var safeCounter=validCitations(input,r.questionId(),r.counterEvidence())?r.counterEvidence():List.<Citation>of();
            Details details=null;
            if(isV3(input)){
                try{details=new GradingEvidenceValidator().validateDetails(input,r,List.of());}catch(IllegalArgumentException invalid){details=new Details(List.of(),List.of(),List.of("필수 판단 근거 또는 검증 가능한 상세 설명을 확보하지 못했습니다."),List.of(),List.of());}
            }
            String heldReason=isV3(input)&&!GradingEvidenceValidator.validNarrative(r.reason(),3,5)?"확인 가능한 기록이 충분하지 않습니다. 부족한 근거를 낮은 단계로 대체하지 않았습니다. 교사의 추가 확인이 필요합니다.":Objects.toString(r.reason(),"판정에 필요한 근거가 부족합니다.");
            return new Cell(r.questionId(),r.criterion(),null,null,"HOLD",heldReason,Objects.toString(r.unmetNextLevel(),""),safeConditions,safeCounter,r.limitations()==null?List.of():r.limitations(),details);
        }
        if(r.level()<minimumLevel(input)||r.level()>maximumLevel(input)||r.reason()==null||r.reason().isBlank()||r.unmetNextLevel()==null||r.conditions()==null||r.counterEvidence()==null||r.limitations()==null)
            return held(r,"INVALID_RECOMMENDATION");
        var matches=new HashMap<String,Condition>();
        for(var condition:r.conditions()) {
            if(condition==null||condition.key()==null||matches.put(condition.key(),condition)!=null||!validCitations(input,r.questionId(),condition.evidence())||condition.evidence().isEmpty())return held(r,"INVALID_CONDITION_EVIDENCE");
            boolean allowed=false;for(int level=minimumLevel(input);level<=maximumLevel(input);level++)if(requiredConditions(r.criterion(),level,isLegacy(input)).contains(condition.key()))allowed=true;
            if(!allowed)return held(r,"UNKNOWN_CONDITION");
        }
        if(!validCitations(input,r.questionId(),r.counterEvidence()))return held(r,"INVALID_COUNTER_EVIDENCE");
        int highest=-1;
        for(int level=minimumLevel(input);level<=maximumLevel(input);level++)if(matches.keySet().containsAll(requiredConditions(r.criterion(),level,isLegacy(input))))highest=level;
        if(highest!=r.level())return held(r,"LEVEL_CONDITIONS_MISMATCH");
        var refs=r.conditions().stream().flatMap(c->c.evidence().stream()).toList();
        boolean studentEvidence=refs.stream().anyMatch(c->input.sources().stream().anyMatch(s->s.id().equals(c.sourceId())&&Set.of("STUDENT_QUESTION","FINAL_ANSWER","ANSWER_CHANGE","DISCLOSURE").contains(s.kind())));
        if(!studentEvidence)return held(r,"NO_STUDENT_BEHAVIOR_EVIDENCE");
        if(r.level()==maximumLevel(input)&&r.criterion()!=Criterion.TRANSPARENCY) {
            boolean answer=refs.stream().anyMatch(c->input.sources().stream().anyMatch(s->s.id().equals(c.sourceId())&&s.kind().equals("FINAL_ANSWER")));
            boolean process=refs.stream().anyMatch(c->input.sources().stream().anyMatch(s->s.id().equals(c.sourceId())&&Set.of("STUDENT_QUESTION","ANSWER_CHANGE","DISCLOSURE").contains(s.kind())));
            if(!answer||!process)return held(r,"FINAL_APPLICATION_NOT_LINKED");
        }
        Details details=null;
        if(isV3(input))try{details=new GradingEvidenceValidator().validateDetails(input,r,requiredConditions(r.criterion(),r.level()));}catch(IllegalArgumentException invalid){return held(r,invalid.getMessage());}
        var conditions=r.conditions();var counter=r.counterEvidence();
        if(isV3(input)){
            var validator=new GradingEvidenceValidator();
            try{conditions=conditions.stream().map(c->new Condition(c.key(),validator.resolveCitations(input,r.questionId(),c.evidence()))).toList();counter=validator.resolveCitations(input,r.questionId(),counter);}catch(IllegalArgumentException invalid){return held(r,invalid.getMessage());}
        }
        return new Cell(r.questionId(),r.criterion(),r.level(),score(input,r.criterion(),r.level()),"RECOMMENDED",r.reason(),r.unmetNextLevel(),conditions,counter,r.limitations(),details);
    }
    private boolean validCitations(Input input,String questionId,List<Citation> citations) {
        if(isV3(input))try{new GradingEvidenceValidator().resolveCitations(input,questionId,citations);return true;}catch(IllegalArgumentException invalid){return false;}
        if(citations==null)return false;
        for(var c:citations)if(c==null||c.quote()==null||c.quote().isBlank()||c.quote().length()>2000||input.sources().stream().noneMatch(s->s.id().equals(c.sourceId())&&s.text().contains(c.quote())
                &&(GLOBAL.equals(questionId)||questionId.equals(s.questionId())||GLOBAL.equals(s.questionId()))))return false;
        return true;
    }
    private Cell held(Recommendation r,String reason) {
        String message=reason!=null&&reason.matches("[A-Z0-9_]+")?"추천 단계와 연결된 원문·설명의 정합성을 확인하지 못했습니다. 해당 항목의 단계를 판단하지 않았습니다. 교사의 추가 확인이 필요합니다.":Objects.toString(reason,"판정에 필요한 근거가 부족합니다.");
        return new Cell(r.questionId(),r.criterion(),null,null,"HOLD",message,Objects.toString(r.unmetNextLevel(),""),List.of(),List.of(),List.of("교사 확인이 필요합니다.","VALIDATION_CODE: "+reason),new Details(List.of(),List.of(),List.of(message),List.of(),List.of()));
    }
    private Cell notApplicable(String q,Criterion c){return new Cell(q,c,null,null,"NOT_APPLICABLE","교사의 사전 정책에 따라 평가 대상이 아닙니다.","",List.of(),List.of(),List.of());}
    public Result notApplicable(Input input,String reason){
        var held=hold(input,reason);var cells=held.cells().stream().map(c->notApplicable(c.questionId(),c.criterion())).toList();
        return calculate(input,cells,held.compliance(),List.of(reason),null);
    }
    public Result hold(Input input,String reason) {
        var cells=new ArrayList<Cell>();
        for(String q:input.questionIds())for(var c:Criterion.values())if(c!=Criterion.TRANSPARENCY)cells.add(excluded(input,c)?notApplicable(q,c):held(new Recommendation(q,c,null,reason,"",List.of(),List.of(),List.of()),reason));
        cells.add(excluded(input,Criterion.TRANSPARENCY)?notApplicable(GLOBAL,Criterion.TRANSPARENCY):held(new Recommendation(GLOBAL,Criterion.TRANSPARENCY,null,reason,"",List.of(),List.of(),List.of()),reason));
        return calculate(input,cells,new Compliance("INSUFFICIENT_EVIDENCE",reason,List.of(),List.of()),List.of(reason),null);
    }
    public Result applyReview(Input input,Result original,List<LevelOverride> overrides) {
        var keys=keys(input);var seen=new HashSet<String>();var cells=new ArrayList<>(original.cells());
        for(var override:overrides) {
            String key=key(override.questionId(),override.criterion());
            if(!keys.contains(key)||!seen.add(key)||override.level()==null||override.level()<minimumLevel(input)||override.level()>maximumLevel(input)||override.reason()==null||override.reason().isBlank())throw new IllegalArgumentException("INVALID_OVERRIDE");
            for(int i=0;i<cells.size();i++)if(key(cells.get(i).questionId(),cells.get(i).criterion()).equals(key)) {
                var old=cells.get(i);cells.set(i,new Cell(old.questionId(),old.criterion(),override.level(),score(input,old.criterion(),override.level()),"TEACHER_REVIEWED",override.reason(),old.unmetNextLevel(),old.conditions(),old.counterEvidence(),old.limitations(),old.details()));
            }
        }
        return calculate(input,cells,original.compliance(),original.limitations(),original.summary());
    }
    private Result calculate(Input input,List<Cell> cells,Compliance compliance,List<String> limitations,String summary) {
        BigDecimal total=BigDecimal.ZERO,assessed=BigDecimal.ZERO,held=BigDecimal.ZERO,notApplicable=BigDecimal.ZERO;
        boolean complete=input.policy()!=null&&input.policy().questionWeights()!=null;
        for(var c:cells){
            BigDecimal factor=BigDecimal.ONE;
            if(c.criterion()!=Criterion.TRANSPARENCY){
                var weight=input.policy()==null?null:input.policy().questionWeights().stream().filter(w->w.questionId().equals(c.questionId())).findFirst().orElse(null);
                if(weight==null){complete=false;continue;}factor=weight.weight().movePointLeft(2);
            }
            BigDecimal maximum=BigDecimal.valueOf(c.criterion().maxScore()).multiply(factor);
            if(c.status().equals("NOT_APPLICABLE")){notApplicable=notApplicable.add(maximum);continue;}
            if(c.score()==null){complete=false;held=held.add(maximum);continue;}
            total=total.add(c.score().multiply(factor));assessed=assessed.add(maximum);
        }
        String status=assessed.signum()==0&&held.signum()==0&&notApplicable.signum()>0?"NOT_APPLICABLE":complete?"DONE":"HOLD";
        var partial=new PartialScore(total.setScale(2,RoundingMode.HALF_UP),assessed.setScale(2,RoundingMode.HALF_UP),held.setScale(2,RoundingMode.HALF_UP),notApplicable.setScale(2,RoundingMode.HALF_UP));
        return new Result(status,compliance,List.copyOf(cells),complete&&assessed.signum()>0?total.setScale(2,RoundingMode.HALF_UP):null,limitations,summary,partial);
    }
    private BigDecimal score(Input input,Criterion c,int level){return BigDecimal.valueOf(c.maxScore()).multiply(BigDecimal.valueOf(level-minimumLevel(input))).divide(BigDecimal.valueOf(4));}
    private Set<String> keys(Input input){var keys=new HashSet<String>();for(String q:input.questionIds())for(var c:Criterion.values())if(c!=Criterion.TRANSPARENCY&&!excluded(input,c))keys.add(key(q,c));if(!excluded(input,Criterion.TRANSPARENCY))keys.add(key(GLOBAL,Criterion.TRANSPARENCY));return keys;}
    private String key(String q,Criterion c){return q+":"+c;}
}
