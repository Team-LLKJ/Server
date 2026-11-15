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
    private final AutomaticDisclosureRepository repository;private final ObjectMapper json;private final PurposeClassifier purposes;
    public AutomaticDisclosureService(AutomaticDisclosureRepository repository,ObjectMapper json,PurposeClassifier purposes){this.repository=repository;this.json=json;this.purposes=purposes;}
    public DisclosureView getDisclosure(String id,String pid){
        repository.lockEvaluation(id);
        var snapshots=repository.getSubmission(pid);
        if(snapshots.isEmpty())return readDisclosure(id,pid);
        try{
            var source=purposes.annotate(pid,json.readTree(snapshots.get(0)));
            var result=generateDisclosure(id,pid,source);
            var stored=repository.getAnalysis(pid);
            var analysis=stored.isEmpty()?null:json.readValue(stored.get(0),AssessmentModels.Analysis.class);
            return refreshDisclosure(pid,source,analysis,result);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private DisclosureView readDisclosure(String id,String pid){
        var stored=repository.getDisclosure(pid);
        if(!stored.isEmpty())try{return json.readValue(stored.get(0),DisclosureView.class);}catch(Exception e){throw new IllegalStateException("Invalid disclosure snapshot",e);}
        return new DisclosureView(id,pid,"","","",null,false,"PENDING","SYSTEM_GENERATED",null,null,List.of(),List.of());
    }
    public void refreshAnalysisDisclosure(String id,String pid,AssessmentModels.Analysis analysis,JsonNode source){
        repository.lockEvaluation(id);
        refreshDisclosure(pid,source,analysis,generateDisclosure(id,pid,source));
    }
    private DisclosureView refreshDisclosure(String pid,JsonNode source,AssessmentModels.Analysis analysis,DisclosureView previous){
        if(!"READY".equals(previous.status())||!"SYSTEM_GENERATED".equals(previous.source()))return previous;
        var result=new AutomaticDisclosureAnalyzer().generateDisclosure(previous.evaluationId(),pid,source,Instant.now(),
                analysis==null?null:analysis.sentences(),analysis==null?null:analysis.semanticStatus());
        if(result.usedParts().equals(previous.usedParts())&&result.reason().equals(previous.reason())&&result.evidence().equals(previous.evidence()))return previous;
        try{repository.updateDisclosure(pid,json.writeValueAsString(result));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
        return result;
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
