package kr.lit.api;

import com.fasterxml.jackson.databind.*;
import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static kr.lit.api.AssessmentModels.*;

final class CommonSettingsRules {
    private CommonSettingsRules() {}
    /** 프런트 선택 항목은 제목 배열입니다. 기존 상세 루브릭 객체 배열도 호환합니다. 둘을 섞지는 않습니다. */
    static List<?> criteria(ObjectMapper json,Validator validator,List<?> values) {
        if(values==null||values.isEmpty())return List.of();
        if(values.size()>10)throw invalidCriteria();
        if(values.stream().allMatch(String.class::isInstance)) {
            var names=values.stream().map(v->((String)v).strip()).toList();
            if(names.stream().anyMatch(n->n.isEmpty()||n.length()>100)||names.stream().distinct().count()!=names.size())throw invalidCriteria();
            return names;
        }
        if(values.stream().anyMatch(v->v==null||v instanceof String))throw invalidCriteria();
        var criteria=new ArrayList<CriterionInput>();
        for(var v:values) {
            CriterionInput c;
            try {c=json.convertValue(v,CriterionInput.class);}catch(IllegalArgumentException ex){throw invalidCriteria();}
            if(!validator.validate(c).isEmpty())throw invalidCriteria();criteria.add(c);
        }
        if(criteria.stream().mapToInt(CriterionInput::maxScore).sum()!=100)throw invalidCriteria();
        return criteria;
    }
    private static ApiError.Failure invalidCriteria() {
        return new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_CRITERIA","평가 항목은 중복 없는 제목1~100자 배열(최대10개) 또는 합계100점의 상세 루브릭 배열이어야 합니다.");
    }
    static JsonNode antiCheat(ObjectMapper json,JsonNode value) {
        if(value==null)return json.createArrayNode();
        boolean valid=value.isObject()||value.isArray();
        JsonNode result=value;
        if(value.isArray()) {
            var codes=new HashSet<String>();var normalized=json.createArrayNode();
            for(var item:value) {
                String code=item.asText().equals("FULLSCREEN")?"FULLSCREEN_LEAVE":item.asText();
                if(!item.isTextual()||!Set.of("TAB_LEAVE","PRINT_BLOCK","FULLSCREEN_LEAVE","DEVTOOLS").contains(code)||!codes.add(code))valid=false;
                normalized.add(code);
            }
            result=normalized;
        }
        if(!valid||value.toString().getBytes(StandardCharsets.UTF_8).length>16384)
            throw new ApiError.Failure(HttpStatus.BAD_REQUEST,"INVALID_ANTI_CHEAT","antiCheat는 TAB_LEAVE·PRINT_BLOCK·FULLSCREEN_LEAVE·DEVTOOLS 코드 배열 또는16KiB 이하 JSON 객체여야 합니다.");
        return result;
    }
    static String kind(List<?> criteria) {return criteria.isEmpty()||criteria.get(0) instanceof String?"TITLES":"RUBRIC";}
    static void saveRubric(org.springframework.jdbc.core.JdbcTemplate db,ObjectMapper json,String id,List<?> criteria) {
        // 제목 선택만으로 임의 배점을 만들지 않습니다. 기존 채점 API의 기본 제안안/교사 지정 기준을 사용합니다.
        if(criteria.isEmpty()||criteria.get(0) instanceof String)return;
        db.update("DELETE FROM evaluation_rubrics WHERE evaluation_id=?",id);
        var rubric=new ArrayList<Criterion>();int n=1;
        for(var value:criteria) {
            var c=json.convertValue(value,CriterionInput.class);
            rubric.add(new Criterion(n++,c.name().strip(),c.maxScore(),c.description()==null?"":c.description(),
                    c.levelDescriptions()==null?List.of("","","","",""):c.levelDescriptions(),c.indicator(),"교사 지정"));
        }
        try {db.update("INSERT INTO evaluation_rubrics(evaluation_id,criteria_json,updated_at) VALUES (?,?,?)",id,json.writeValueAsString(rubric),java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException(ex);}
    }
}
