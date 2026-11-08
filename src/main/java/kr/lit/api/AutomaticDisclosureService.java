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
    public DisclosureView generateDisclosure(String id,String pid,JsonNode source){
        repository.lockEvaluation(id);var previous=readDisclosure(id,pid);if(!previous.status().equals("PENDING"))return previous;
        DisclosureView result;
        try{result=new AutomaticDisclosureAnalyzer().generateDisclosure(id,pid,source,Instant.now());}
        catch(RuntimeException e){result=new DisclosureView(id,pid,"","","",Instant.now(),false,"FAILED","SYSTEM_GENERATED","DISCLOSURE_GENERATION_FAILED","기록 분석에 실패했습니다.",List.of(),List.of());}
        try{repository.saveDisclosure(id,pid,json.writeValueAsString(result));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return result;
    }
}
