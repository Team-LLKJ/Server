package kr.lit.api;

import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import static kr.lit.api.WorkModels.*;

@Service
@Transactional
public class AutomaticDisclosureService {
    private final AutomaticDisclosureRepository repository;private final ObjectMapper json;
    public AutomaticDisclosureService(AutomaticDisclosureRepository repository,ObjectMapper json){this.repository=repository;this.json=json;}
    public DisclosureView getDisclosure(String id,String pid){
        var result=readDisclosure(id,pid);
        if(result.status().equals("PENDING")){
            var snapshots=repository.getSubmission(pid);
            if(!snapshots.isEmpty())try{return generateDisclosure(id,pid,json.readTree(snapshots.get(0)));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        }
        return result;
    }
    private DisclosureView readDisclosure(String id,String pid){
        var stored=repository.getDisclosure(pid);
        if(!stored.isEmpty())try{return json.readValue(stored.get(0),DisclosureView.class);}catch(Exception e){throw new IllegalStateException("Invalid disclosure snapshot",e);}
        return new DisclosureView(id,pid,"","","",null,false,"PENDING","SYSTEM_GENERATED",null,null,List.of(),List.of());
    }
    /**
     * 문장 출처 판정(글자 비교 V2·LLM·교사 수정)이 바뀌면 자동 기록의 "답안에서 AI를 쓴 부분"을 같은 판정으로 다시 만듭니다.
     * 저장된 기록이 READY일 때만 바꿉니다(FAILED·PENDING·학생 작성 기록은 그대로).
     */
    public void refreshUsedParts(String id,String pid,AssessmentModels.Analysis analysis){
        if(analysis==null||analysis.sentences()==null)return;
        var stored=repository.getDisclosure(pid);if(stored.isEmpty())return;
        try{
            var previous=json.readValue(stored.get(0),DisclosureView.class);
            if(!"READY".equals(previous.status())||!"SYSTEM_GENERATED".equals(previous.source()))return;
            var snapshots=repository.getSubmission(pid);if(snapshots.isEmpty())return;
            var source=json.readTree(snapshots.get(0));
            var paragraphs=new java.util.HashMap<Long,java.util.TreeSet<Integer>>();
            var questionOf=new java.util.HashMap<Long,String>();
            for(var turn:source.path("conversations"))questionOf.put(turn.path("id").asLong(),turn.path("questionId").asText());
            for(var s:analysis.sentences()){
                if(!AssessmentAnalyzer.SENTENCE_AI.equals(s.source())||s.conversationId()==null||s.paragraphId()==null)continue;
                if(!s.questionId().equals(questionOf.get(s.conversationId())))continue;
                int at=s.paragraphId().lastIndexOf("-p");if(at<0)continue;
                try{paragraphs.computeIfAbsent(s.conversationId(),k->new java.util.TreeSet<>()).add(Integer.parseInt(s.paragraphId().substring(at+2)));}catch(NumberFormatException ignored){}
            }
            var map=new java.util.HashMap<Long,List<Integer>>();paragraphs.forEach((k,v)->map.put(k,List.copyOf(v)));
            var result=new AutomaticDisclosureAnalyzer().generateDisclosure(id,pid,source,Instant.now(),map);
            if(result.usedParts().equals(previous.usedParts())&&result.evidence().equals(previous.evidence()))return;
            repository.updateDisclosure(pid,json.writeValueAsString(result));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public DisclosureView generateDisclosure(String id,String pid,JsonNode source){
        repository.lockEvaluation(id);var previous=readDisclosure(id,pid);if(!previous.status().equals("PENDING"))return previous;
        DisclosureView result;
        try{result=new AutomaticDisclosureAnalyzer().generateDisclosure(id,pid,source,Instant.now());}
        catch(RuntimeException e){result=new DisclosureView(id,pid,"","","",Instant.now(),false,"FAILED","SYSTEM_GENERATED","DISCLOSURE_GENERATION_FAILED","기록 분석에 실패했습니다.",List.of(),List.of());}
        try{repository.saveDisclosure(id,pid,json.writeValueAsString(result));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return result;
    }
}
