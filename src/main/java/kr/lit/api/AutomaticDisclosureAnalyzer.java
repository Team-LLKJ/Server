package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.WorkModels.*;

/** Recorded requests classify observable use; they do not establish authorship or student intent. */
public class AutomaticDisclosureAnalyzer {
    public DisclosureView generateDisclosure(String id,String pid,JsonNode source,Instant now){return generateDisclosure(id,pid,source,now,null);}
    /**
     * aiParagraphs: 대화 id → 그 문항 최종 답안에서 문장 판정(글자 비교·LLM·교사 수정)으로 AI 문장이 나온 문단 번호(1부터).
     * 주면 "답안에서 AI를 쓴 부분"을 문장 판정으로 만들고, null이면 제출 시점처럼 문단 전체가 AI 답변에 그대로 있는지로 봅니다.
     */
    public DisclosureView generateDisclosure(String id,String pid,JsonNode source,Instant now,Map<Long,List<Integer>> aiParagraphs){
        var tools=new TreeSet<String>();var ids=new ArrayList<Long>();var evidence=new ArrayList<DisclosureEvidence>();
        var groups=new LinkedHashMap<String,Integer>();var purposes=new LinkedHashMap<String,Integer>();int completed=0,failed=0;
        var answerTexts=new HashMap<String,List<String>>();var answerVersions=new HashMap<String,Integer>();
        var positions=new HashMap<String,Integer>();
        for(var a:source.path("answers")){
            String q=a.path("question").path("id").asText();positions.put(q,a.path("question").path("position").asInt());
            answerTexts.put(q,AssessmentAnalyzer.paragraphs(a.path("answer").path("content").asText()));answerVersions.put(q,a.path("answer").path("version").asInt());
        }
        for(var turn:source.path("conversations")){
            long cid=turn.path("id").asLong();if(cid<=0)throw new IllegalArgumentException("Missing conversation identifier");ids.add(cid);
            String qid=turn.path("questionId").asText();String prompt=turn.path("questionText").asText();
            String purpose=switch(AssessmentAnalyzer.classify(turn).name()){
                case "CONCEPT_UNDERSTANDING"->"개념 확인";case "RESEARCH"->"자료 탐색";case "IDEA_EXPLORATION"->"아이디어 구체화";
                case "ANSWER_DRAFTING"->"답안 작성 요청";case "VERIFICATION"->"검증 요청";case "EDIT"->"표현 수정 요청";default->"기타 요청";};
            purposes.merge(purpose,1,Integer::sum);
            boolean usable="COMPLETED".equals(turn.path("state").asText())&&!turn.path("answerText").asText().isBlank();
            if(usable){completed++;tools.add("LIT 내장 AI ("+turn.path("provider").asText("미기록")+" / "+turn.path("model").asText("미기록")+")");}else failed++;
            String location=positions.containsKey(qid)?"문항 "+positions.get(qid):"문항 "+qid;
            var matching=new ArrayList<Integer>();
            if(usable&&aiParagraphs!=null) matching.addAll(aiParagraphs.getOrDefault(cid,List.of()));
            else if(usable){
                String response=turn.path("answerText").asText();var paragraphs=answerTexts.getOrDefault(qid,List.of());
                for(int i=0;i<paragraphs.size();i++){
                    String paragraph=paragraphs.get(i);
                    if(paragraph.length()>=20&&response.contains(paragraph))matching.add(i+1);
                }
            }
            String part=matching.isEmpty()?"":aiParagraphs!=null?" (최종 답안 문단 "+matching+"에 이 대화의 AI 답변을 쓴 문장)":" (최종 답안 문단 "+matching+"에 AI 응답과 동일한 문구 관측)";
            groups.merge(location+": "+purpose+(usable?part:" (완료 응답 없음)"),1,Integer::sum);
            evidence.add(new DisclosureEvidence(cid,qid,purpose,"USER",prompt,List.copyOf(matching),answerVersions.getOrDefault(qid,0)));
        }
        String used=ids.isEmpty()?"LIT에 기록된 AI 대화가 없습니다.":"시스템이 기록된 질문을 분류한 결과: "+String.join("; ",groups.entrySet().stream().map(e->e.getKey()+" "+e.getValue()+"건").toList());
        String reason=ids.isEmpty()?"외부 AI 사용 여부는 확인할 수 없습니다.":"시스템 관측: 질문 "+ids.size()+"건, 완료 응답 "+completed+"건, 실패·미완료 "+failed+"건. 시스템 분류: "+String.join(", ",purposes.entrySet().stream().map(e->e.getKey()+" "+e.getValue()+"건").toList())+". 활용 목적은 질문 표현에 따른 규칙 기반 추정이며 실제 의도·답안 반영 위치를 확정하지 않습니다.";
        return new DisclosureView(id,pid,String.join("\n",tools),used,reason,now,false,"READY","SYSTEM_GENERATED",null,null,List.copyOf(ids),List.copyOf(evidence));
    }
}
