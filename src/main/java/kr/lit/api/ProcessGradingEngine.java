package kr.lit.api;

import org.springframework.stereotype.Component;
import java.math.*;
import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

/** 근거 인용·충족 조건 검증 및 점수 계산. 외부 AI·DB·시계에 접근하지 않습니다. */
@Component
public class ProcessGradingEngine {
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
        for(String q:input.questionIds())for(var c:Criterion.values())if(c!=Criterion.TRANSPARENCY)cells.add(validate(input,recommended.get(key(q,c))));
        cells.add(validate(input,recommended.get(key(GLOBAL,Criterion.TRANSPARENCY))));
        var compliance=output.compliance();
        if(!Set.of("OK","CHECK","INSUFFICIENT_EVIDENCE").contains(Objects.toString(compliance.status(),""))||!validCitations(input,GLOBAL,compliance.evidence())
                ||compliance.reason()==null||compliance.reason().isBlank()||(!"INSUFFICIENT_EVIDENCE".equals(compliance.status())&&compliance.evidence().isEmpty()))
            compliance=new Compliance("INSUFFICIENT_EVIDENCE","규정 준수 근거를 검증할 수 없습니다.",List.of(),List.of("위반 확정은 교사가 판단합니다."));
        return calculate(input,cells,compliance,List.of("LIT 제안 척도·교사 확인 필요. 인용문 검증은 의미 판단의 정확성을 보장하지 않습니다.","교과 지식 정확성·경고 횟수·유사도를 자동 감점하지 않습니다."));
    }
    private Cell validate(Input input,Recommendation r) {
        if(r.level()==null) {
            var safeConditions=r.conditions()==null?List.<Condition>of():r.conditions().stream().filter(c->c!=null&&c.key()!=null&&validCitations(input,r.questionId(),c.evidence())).toList();
            var safeCounter=validCitations(input,r.questionId(),r.counterEvidence())?r.counterEvidence():List.<Citation>of();
            return new Cell(r.questionId(),r.criterion(),null,null,"HOLD","판단 보류: "+Objects.toString(r.reason(),"근거 부족"),Objects.toString(r.unmetNextLevel(),""),safeConditions,safeCounter,r.limitations()==null?List.of():r.limitations());
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
        return new Cell(r.questionId(),r.criterion(),r.level(),score(input,r.criterion(),r.level()),"RECOMMENDED",r.reason(),r.unmetNextLevel(),r.conditions(),r.counterEvidence(),r.limitations());
    }
    private boolean validCitations(Input input,String questionId,List<Citation> citations) {
        if(citations==null)return false;
        for(var c:citations)if(c==null||c.quote()==null||c.quote().isBlank()||c.quote().length()>2000||input.sources().stream().noneMatch(s->s.id().equals(c.sourceId())&&s.text().contains(c.quote())
                &&(GLOBAL.equals(questionId)||questionId.equals(s.questionId())||GLOBAL.equals(s.questionId()))))return false;
        return true;
    }
    private Cell held(Recommendation r,String reason) {
        return new Cell(r.questionId(),r.criterion(),null,null,"HOLD",reason,Objects.toString(r.unmetNextLevel(),""),List.of(),List.of(),List.of("자동 평가 보류·교사 확인 필요"));
    }
    public Result hold(Input input,String reason) {
        var cells=new ArrayList<Cell>();
        for(String q:input.questionIds())for(var c:Criterion.values())if(c!=Criterion.TRANSPARENCY)cells.add(held(new Recommendation(q,c,null,reason,"",List.of(),List.of(),List.of()),reason));
        cells.add(held(new Recommendation(GLOBAL,Criterion.TRANSPARENCY,null,reason,"",List.of(),List.of(),List.of()),reason));
        return new Result("HOLD",new Compliance("INSUFFICIENT_EVIDENCE",reason,List.of(),List.of()),cells,null,List.of(reason));
    }
    public Result applyReview(Input input,Result original,List<LevelOverride> overrides) {
        var keys=keys(input);var seen=new HashSet<String>();var cells=new ArrayList<>(original.cells());
        for(var override:overrides) {
            String key=key(override.questionId(),override.criterion());
            if(!keys.contains(key)||!seen.add(key)||override.level()==null||override.level()<minimumLevel(input)||override.level()>maximumLevel(input)||override.reason()==null||override.reason().isBlank())throw new IllegalArgumentException("INVALID_OVERRIDE");
            for(int i=0;i<cells.size();i++)if(key(cells.get(i).questionId(),cells.get(i).criterion()).equals(key)) {
                var old=cells.get(i);cells.set(i,new Cell(old.questionId(),old.criterion(),override.level(),score(input,old.criterion(),override.level()),"TEACHER_REVIEWED",override.reason(),old.unmetNextLevel(),old.conditions(),old.counterEvidence(),old.limitations()));
            }
        }
        return calculate(input,cells,original.compliance(),original.limitations());
    }
    private Result calculate(Input input,List<Cell> cells,Compliance compliance,List<String> limitations) {
        BigDecimal total=BigDecimal.ZERO;boolean complete=input.policy()!=null&&input.policy().questionWeights()!=null;
        for(var c:cells) {
            if(c.score()==null){complete=false;continue;}
            if(c.criterion()==Criterion.TRANSPARENCY)total=total.add(c.score());
            else {
                var weight=input.policy()==null?null:input.policy().questionWeights().stream().filter(w->w.questionId().equals(c.questionId())).findFirst().orElse(null);
                if(weight==null)complete=false;else total=total.add(c.score().multiply(weight.weight()).movePointLeft(2));
            }
        }
        return new Result(complete?"DONE":"HOLD",compliance,List.copyOf(cells),complete?total.setScale(2,RoundingMode.HALF_UP):null,limitations);
    }
    private BigDecimal score(Input input,Criterion c,int level){return BigDecimal.valueOf(c.maxScore()).multiply(BigDecimal.valueOf(level-minimumLevel(input))).divide(BigDecimal.valueOf(4));}
    private Set<String> keys(Input input){var keys=new HashSet<String>();for(String q:input.questionIds())for(var c:Criterion.values())if(c!=Criterion.TRANSPARENCY)keys.add(key(q,c));keys.add(key(GLOBAL,Criterion.TRANSPARENCY));return keys;}
    private String key(String q,Criterion c){return q+":"+c;}
}
