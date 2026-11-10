package kr.lit.api;

import java.util.*;
import static kr.lit.api.ProcessGradingModels.*;

/** 원문 위치와 설명의 구조적 정합성을 검증합니다. 의미 정확성은 교사 확인 대상입니다. */
public class GradingEvidenceValidator {
    public List<Citation> resolveCitations(Input input,String questionId,List<Citation> citations){
        if(citations==null)throw new IllegalArgumentException("INVALID_CITATION");
        var result=new ArrayList<Citation>();
        for(var c:citations){
            if(c==null||c.quote()==null||c.quote().isBlank()||c.quote().length()>2000)throw new IllegalArgumentException("INVALID_CITATION");
            var matches=input.sources().stream().filter(s->s.id().equals(c.sourceId())&&(GLOBAL.equals(questionId)||questionId.equals(s.questionId())||GLOBAL.equals(s.questionId()))).toList();
            if(matches.size()!=1)throw new IllegalArgumentException("INVALID_CITATION_SOURCE");
            var source=matches.get(0);String text=source.text();if(text==null)throw new IllegalArgumentException("INVALID_CITATION_SOURCE");int start;
            if(c.start()==null&&c.end()==null){start=text.indexOf(c.quote());if(start<0||text.indexOf(c.quote(),start+1)>=0)throw new IllegalArgumentException("AMBIGUOUS_CITATION");}
            else if(c.start()!=null&&c.end()!=null){start=c.start();if(c.end()!=start+c.quote().length())throw new IllegalArgumentException("INVALID_CITATION_RANGE");}
            else throw new IllegalArgumentException("INVALID_CITATION_RANGE");
            int end=start+c.quote().length();if(!InputPathAnalyzer.validBoundary(text,start)||!InputPathAnalyzer.validBoundary(text,end)||!text.substring(start,end).equals(c.quote()))throw new IllegalArgumentException("INVALID_CITATION_RANGE");
            int offset=Objects.requireNonNullElse(source.startOffset(),0);
            var location=new EvidenceLocation(source.questionId(),source.conversationId(),source.messageRole(),source.answerVersion(),source.kind().equals("FINAL_ANSWER")?"FINAL_ANSWER_TEXT":"SOURCE_TEXT",offset+start,offset+end);
            result.add(new Citation(c.sourceId(),c.quote(),start,end,location));
        }
        return List.copyOf(result);
    }
    public static boolean validNarrative(String text,int min,int max){
        if(text==null||text.isBlank()||text.contains("\n")||text.length()>12000)return false;
        var sentences=text.strip().split("(?<=[.!?。])\\s+");
        if(sentences.length<min||sentences.length>max)return false;
        return Arrays.stream(sentences).allMatch(s->s.matches("(?s).*(?:습니다|입니다|합니다|됩니다)[.!?。]"));
    }
    public Details validateDetails(Input input,Recommendation recommendation,List<String> required){
        var d=recommendation.details();
        if(!validNarrative(recommendation.reason(),3,5)||d==null||d.credits()==null||d.deductions()==null||d.confirmationNeeded()==null||d.improvements()==null||d.findings()==null)throw new IllegalArgumentException("INVALID_CRITERION_NARRATIVE");
        var credits=resolveReasons(input,recommendation.questionId(),d.credits());var deductions=resolveReasons(input,recommendation.questionId(),d.deductions());
        if(recommendation.level()!=null&&credits.isEmpty()&&deductions.isEmpty())throw new IllegalArgumentException("BEHAVIOR_EXPLANATION_REQUIRED");
        var findings=new ArrayList<ConditionFinding>();var statuses=new HashMap<String,String>();
        for(var finding:d.findings()){
            if(finding==null||finding.key()==null||!finding.key().matches(recommendation.criterion().name()+"_(L[1-5]|FINAL_APPLICATION)")||!Set.of("MET","UNMET","UNKNOWN").contains(Objects.toString(finding.status(),""))||statuses.put(finding.key(),finding.status())!=null||!validNarrative(finding.explanation(),1,5))throw new IllegalArgumentException("INVALID_CONDITION_FINDING");
            var cites=resolveCitations(input,recommendation.questionId(),finding.evidence());
            if(recommendation.level()!=null&&finding.essential()&&finding.status().equals("UNKNOWN"))throw new IllegalArgumentException("ESSENTIAL_EVIDENCE_MISSING");
            if(!finding.status().equals("UNKNOWN")&&cites.isEmpty())throw new IllegalArgumentException("CONDITION_EVIDENCE_REQUIRED");
            findings.add(new ConditionFinding(finding.key(),finding.status(),finding.essential(),finding.explanation(),cites));
        }
        if(required.stream().anyMatch(k->!"MET".equals(statuses.get(k))))throw new IllegalArgumentException("EXPLANATION_LEVEL_CONTRADICTION");
        for(var condition:recommendation.conditions())if(statuses.containsKey(condition.key())&&!"MET".equals(statuses.get(condition.key())))throw new IllegalArgumentException("EXPLANATION_LEVEL_CONTRADICTION");
        if(d.confirmationNeeded().stream().anyMatch(Objects::isNull)||d.improvements().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException("INVALID_CRITERION_NARRATIVE");
        return new Details(credits,deductions,List.copyOf(d.confirmationNeeded()),List.copyOf(d.improvements()),List.copyOf(findings));
    }
    private List<BehaviorReason> resolveReasons(Input input,String questionId,List<BehaviorReason> reasons){
        var result=new ArrayList<BehaviorReason>();var seen=new HashSet<String>();
        for(var reason:reasons){
            if(reason==null||!validNarrative(reason.title(),1,1)||!validNarrative(reason.explanation(),2,3))throw new IllegalArgumentException("INVALID_BEHAVIOR_NARRATIVE");
            var citations=resolveCitations(input,questionId,reason.evidence());if(citations.isEmpty())throw new IllegalArgumentException("BEHAVIOR_EVIDENCE_REQUIRED");
            String fingerprint=citations.stream().map(c->c.sourceId()+":"+c.start()+":"+c.end()).sorted().reduce("",(a,b)->a+"|"+b);
            if(!seen.add(fingerprint))throw new IllegalArgumentException("DUPLICATE_BEHAVIOR_REASON");
            result.add(new BehaviorReason(reason.title(),reason.explanation(),citations));
        }
        return List.copyOf(result);
    }
}
