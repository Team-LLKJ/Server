package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** 목적 분류는 점수가 아니며 질문 하나의 총 가중치는 항상1입니다. */
public class PurposeUsageAnalyzer {
    public enum Purpose { IDEATION, CONCEPT, RESEARCH, STRUCTURING, GENERATION, CRITICAL_REVIEW, EXPRESSION, OTHER }
    private static final Map<Purpose,Pattern> RULES=Map.of(
        Purpose.IDEATION,Pattern.compile("아이디어|브레인|발상|idea"),Purpose.CONCEPT,Pattern.compile("개념|설명|이해|원리|정의|뜻|힌트"),
        Purpose.RESEARCH,Pattern.compile("자료|출처|검색|조사|통계|논문"),Purpose.STRUCTURING,Pattern.compile("구조|개요|목차|구성|정리"),
        Purpose.GENERATION,Pattern.compile("답안|대신|작성해|써\\s*줘|풀어|완성"),Purpose.CRITICAL_REVIEW,Pattern.compile("검토|확인|검증|오류|반론|타당|맞는|비교"),
        Purpose.EXPRESSION,Pattern.compile("표현|문장|맞춤법|교정|다듬|고쳐|문체"));
    public record Question(long conversationId,String questionId,String quote,List<Purpose> purposes,double weightPerPurpose) {}
    public record Group(Purpose purpose,String name,double percentage,List<Question> representativeQuestions) {}
    public record View(String method,String status,int questionCount,int missingRecords,List<Group> usage,List<Question> questions,List<String> limitations) {}
    public View analyzeUsage(JsonNode snapshot){
        var questions=new ArrayList<Question>();int missing=0;
        if(!snapshot.path("conversations").isArray())return new View("KEYWORD_MULTI_LABEL_V3","RECORDS_UNAVAILABLE",0,0,List.of(),List.of(),List.of("대화 자료를 확보하지 못했습니다. 질문 기록 없음과 구분합니다."));
        var seen=new HashSet<Long>();
        for(var turn:snapshot.path("conversations")){
            String text=turn.path("questionText").asText("");long id=turn.path("id").asLong();
            if(text.isBlank()||id<=0||!seen.add(id)){missing++;continue;}
            var purposes=new ArrayList<Purpose>();for(var purpose:Purpose.values())if(RULES.containsKey(purpose)&&RULES.get(purpose).matcher(text.toLowerCase(Locale.ROOT)).find())purposes.add(purpose);
            if(purposes.isEmpty())purposes.add(Purpose.OTHER);
            questions.add(new Question(id,turn.path("questionId").asText(),text,List.copyOf(purposes),1.0/purposes.size()));
            if(!"COMPLETED".equals(turn.path("state").asText()))missing++;
        }
        var weights=new EnumMap<Purpose,Double>(Purpose.class);for(var q:questions)for(var p:q.purposes())weights.merge(p,q.weightPerPurpose(),Double::sum);
        var active=Arrays.stream(Purpose.values()).filter(weights::containsKey).toList();var units=new EnumMap<Purpose,Integer>(Purpose.class);int assigned=0;
        for(var p:active){int n=(int)Math.floor(weights.get(p)*1000/questions.size()+1e-9);units.put(p,n);assigned+=n;}
        if(!questions.isEmpty()){
            var order=new ArrayList<>(active);order.sort(Comparator.<Purpose>comparingDouble(p->-(weights.get(p)*1000/questions.size()-units.get(p))).thenComparingInt(Enum::ordinal));
            for(int i=0;i<1000-assigned;i++)units.merge(order.get(i),1,Integer::sum);
        }
        var groups=active.stream().map(p->new Group(p,labelPurpose(p),units.get(p)/10.0,questions.stream().filter(q->q.purposes().contains(p)).limit(3).toList())).toList();
        return new View("KEYWORD_MULTI_LABEL_V3",missing>0?"PARTIAL":questions.isEmpty()?"NO_RECORDS":"READY",questions.size(),missing,groups,List.copyOf(questions),List.of("질문 표현의 규칙 기반 분류이며 의미 판단의 정확성을 보장하지 않습니다.","한 질문의 목적별 가중치 합은1입니다. 비율을 높음·보통·낮음이나 점수로 바꾸지 않습니다."));
    }
    private String labelPurpose(Purpose p){return switch(p){case IDEATION->"아이디어 탐색";case CONCEPT->"개념 이해";case RESEARCH->"자료 조사";case STRUCTURING->"구조화";case GENERATION->"답안 생성";case CRITICAL_REVIEW->"비판적 검토";case EXPRESSION->"표현 수정";case OTHER->"기타";};}
}
