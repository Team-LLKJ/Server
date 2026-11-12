package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AI 사용 방법(질문 용도) 분석. 질문 하나에 주된 용도 하나만 매기고, 비율은 "질문 몇 개 중 몇 개"입니다. 점수가 아닙니다.
 * 용도는 스냅샷 대화에 붙은 purposeClassification(교사 확정 > LLM: Gemini 3회 일치, 불일치는 Haiku 임시 용도)을 쓰고,
 * 아직 분류 전이거나 실패한 질문은 키워드 규칙으로 대체해 method에 표시합니다. AssessmentAnalyzer.classify와 같은 결과를 씁니다.
 */
public class PurposeUsageAnalyzer {
    public enum Purpose {
        UNDERSTAND("이해·조사"), PLAN("아이디어·구성"), REVIEW("비판적 검토"), EDIT("표현 수정"), DRAFT("답안 생성"), OTHER("기타"), UNDECIDED("판단 보류");
        public final String label;
        Purpose(String label){this.label=label;}
        /** 비율(usage)에 들어가는 5개 항목. OTHER는 otherCount, UNDECIDED는 비율에서 뺍니다. */
        public boolean counted(){return this!=OTHER&&this!=UNDECIDED;}
        public static Optional<Purpose> parse(String value) {
            try { return value==null||value.isBlank()?Optional.empty():Optional.of(valueOf(value.strip())); }
            catch(IllegalArgumentException ex) { return Optional.empty(); }
        }
    }
    public static final String METHOD_LLM="LLM_GEMINI_X3",METHOD_LLM_HAIKU="LLM_GEMINI_X3_HAIKU",METHOD_TEACHER="TEACHER",METHOD_KEYWORD="KEYWORD_FALLBACK";
    /** 키워드 대체 규칙(우선순위 순서대로 첫 일치). LLM 분류가 없거나 실패한 질문에만 씁니다. */
    private static final List<Map.Entry<Purpose,Pattern>> KEYWORDS=List.of(
        Map.entry(Purpose.DRAFT,Pattern.compile("답안|정답|대신|작성해|써\\s*줘|써줘|풀어\\s*줘|완성해|만들어\\s*줘|이어\\s*(써|쓰)|덧붙|붙여\\s*줘|늘려\\s*줘|추가해\\s*줘")),
        Map.entry(Purpose.EDIT,Pattern.compile("고쳐|맞춤법|교정|다듬|어색|문체|어조|바꿔\\s*줘|자연스럽게")),
        Map.entry(Purpose.REVIEW,Pattern.compile("맞아|맞니|맞는지|맞나|검토|확인해|검증|오류|틀린|틀렸|출처|근거|사실인지|정확한지|반박|타당|진짜야|누가\\s*센")),
        Map.entry(Purpose.PLAN,Pattern.compile("아이디어|브레인|발상|구조|개요|목차|구성|순서|방향|주제|서론|결론|어떻게\\s*쓸|뭘\\s*쓸|무엇을\\s*쓸")),
        Map.entry(Purpose.UNDERSTAND,Pattern.compile("개념|설명|이해|원리|정의|뜻|의미|자료|조사|검색|통계|사례|예시|논문|알려|왜|무엇|뭐야|차이|효과|영향|힌트")));
    static Purpose keyword(String text) {
        String t=text==null?"":text.toLowerCase(Locale.ROOT);
        for(var rule:KEYWORDS) if(rule.getValue().matcher(t).find()) return rule.getKey();
        return Purpose.OTHER;
    }

    /**
     * 질문별 결과. purpose는 최종 용도(교사 확정 > LLM 확정·임시 > 키워드), votes는 Gemini 3회 답, haikuPurpose는 불일치 때 Haiku 답.
     * purposes·weightPerPurpose는 이전 응답과의 호환용(항상 [purpose], 1.0).
     */
    public record Question(long conversationId,String questionId,String quote,Purpose purpose,
                           @io.swagger.v3.oas.annotations.media.Schema(description="Gemini 3회 답(용도 값)") List<String> votes,
                           @io.swagger.v3.oas.annotations.media.Schema(description="Gemini가 엇갈렸을 때 Haiku 답, 아니면 null",allowableValues={"UNDERSTAND","PLAN","REVIEW","EDIT","DRAFT","OTHER"},nullable=true) String haikuPurpose,
                           boolean needsReview,@io.swagger.v3.oas.annotations.media.Schema(allowableValues={"UNDERSTAND","PLAN","REVIEW","EDIT","DRAFT","OTHER"},nullable=true) String teacherPurpose,
                           @io.swagger.v3.oas.annotations.media.Schema(allowableValues={"LLM_GEMINI_X3","LLM_GEMINI_X3_HAIKU","TEACHER","KEYWORD_FALLBACK"}) String method,List<Purpose> purposes,double weightPerPurpose,
                           String reason,List<String> cues,List<RubricImpact> rubricImpacts) {
        public Question {
            cues=cues==null?List.of():List.copyOf(cues);
            rubricImpacts=rubricImpacts==null?List.of():List.copyOf(rubricImpacts);
        }
    }
    public record RubricImpact(AssessmentModels.Indicator criterion,String effect,String reason) {}

    public record Group(Purpose purpose,String name,double percentage,int count,List<Question> representativeQuestions) {}
    /**
     * usage: 5개 항목 비율(확정+임시 용도, OTHER·UNDECIDED 제외). reviewCount: 교사 확인 필요 질문 수. otherCount: 기타 질문 수.
     * undecidedCount: Gemini 세 답이 모두 다르고 Haiku도 실패한 질문 수. pendingCount: 아직 LLM 분류 전이라 키워드로 대체한 질문 수.
     */
    public record View(String method,String status,int questionCount,int missingRecords,List<Group> usage,List<Question> questions,List<String> limitations,
                       int reviewCount,int otherCount,int undecidedCount,int pendingCount) {}

    public View analyzeUsage(JsonNode snapshot){
        if(!snapshot.path("conversations").isArray())return new View(METHOD_KEYWORD,"RECORDS_UNAVAILABLE",0,0,List.of(),List.of(),
                List.of("대화 자료를 확보하지 못했습니다. 질문 기록 없음과 구분합니다."),0,0,0,0);
        var questions=new ArrayList<Question>();int missing=0,pending=0;var seen=new HashSet<Long>();var methods=new LinkedHashSet<String>();
        for(var turn:snapshot.path("conversations")){
            String text=turn.path("questionText").asText("");long id=turn.path("id").asLong();
            if(text.isBlank()||id<=0||!seen.add(id)){missing++;continue;}
            var c=turn.path("purposeClassification");
            var purpose=Purpose.parse(c.path("purpose").asText(null)).orElse(null);
            String method;
            if(purpose==null){purpose=keyword(text);method=METHOD_KEYWORD;if(c.path("status").asText("").equals("PENDING")||c.isMissingNode())pending++;}
            else method=c.path("method").asText(METHOD_LLM);
            var votes=new ArrayList<String>();for(var v:c.path("votes"))votes.add(v.asText());
            String teacher=c.path("teacherPurpose").isTextual()?c.path("teacherPurpose").asText():null;
            questions.add(new Question(id,turn.path("questionId").asText(),text,purpose,List.copyOf(votes),c.path("haikuPurpose").isTextual()?c.path("haikuPurpose").asText():null,
                    teacher==null&&c.path("needsReview").asBoolean(false),teacher,method,List.of(purpose),1.0,
                    teacher==null&&c.path("reason").isTextual()?c.path("reason").asText():getReason(purpose,method,text),
                    teacher==null&&c.path("cues").isArray()?getStoredCues(c.path("cues"),text):getCues(purpose,text),List.of()));
            methods.add(method);
            if(!"COMPLETED".equals(turn.path("state").asText()))missing++;
        }
        var counted=questions.stream().filter(q->q.purpose().counted()).toList();
        var counts=new EnumMap<Purpose,Integer>(Purpose.class);for(var q:counted)counts.merge(q.purpose(),1,Integer::sum);
        var tenths=percentTenths(counts,counted.size());
        var groups=Arrays.stream(Purpose.values()).filter(counts::containsKey).map(p->new Group(p,p.label,tenths.get(p)/10.0,counts.get(p),
                counted.stream().filter(q->q.purpose()==p).limit(3).toList())).toList();
        int review=(int)questions.stream().filter(Question::needsReview).count();
        int other=(int)questions.stream().filter(q->q.purpose()==Purpose.OTHER).count();
        int undecided=(int)questions.stream().filter(q->q.purpose()==Purpose.UNDECIDED).count();
        String method=methods.isEmpty()?METHOD_KEYWORD:methods.size()==1?methods.iterator().next():"MIXED";
        var limitations=new ArrayList<String>();
        limitations.add("질문 하나에 주된 용도 하나만 매긴 비율입니다(질문 몇 개 중 몇 개). 점수가 아닙니다.");
        if(review>0)limitations.add("Gemini 세 번의 분류가 엇갈린 질문 "+review+"개는 임시 용도로 계산했으며 교사 확인이 필요합니다.");
        if(undecided>0)limitations.add("용도를 정하지 못한 질문 "+undecided+"개는 비율에서 뺐습니다.");
        if(methods.contains(METHOD_KEYWORD))limitations.add("LLM 분류 전이거나 실패한 질문은 키워드 규칙으로 대체했습니다(method=KEYWORD_FALLBACK).");
        return new View(method,missing>0?"PARTIAL":questions.isEmpty()?"NO_RECORDS":"READY",questions.size(),missing,groups,List.copyOf(questions),
                List.copyOf(limitations),review,other,undecided,pending);
    }
    private List<String> getStoredCues(JsonNode cues,String text) {
        var out=new LinkedHashSet<String>();
        for (var cue:cues) if (cue.isTextual()&&!cue.asText().isBlank()&&text.contains(cue.asText())) out.add(cue.asText());
        return List.copyOf(out);
    }

    private List<String> getCues(Purpose purpose,String text) {
        return KEYWORDS.stream().filter(rule->rule.getKey()==purpose).findFirst()
                .map(rule->rule.getValue().matcher(text).results().map(java.util.regex.MatchResult::group).distinct().limit(5).toList())
                .orElse(List.of());
    }

    private String getReason(Purpose purpose,String method,String text) {
        if (METHOD_TEACHER.equals(method)) return "교사가 이 질문의 용도를 "+purpose.label+"으로 확정했습니다.";
        if (purpose==Purpose.UNDECIDED) return "모델 판정이 엇갈려 질문의 용도를 확인해야 합니다.";
        var cues=getCues(purpose,text);
        if (METHOD_KEYWORD.equals(method)&&!cues.isEmpty()) return "질문의 ‘"+cues.get(0)+"’ 표현이 "+purpose.label+" 분류 규칙에 해당합니다.";
        if (METHOD_KEYWORD.equals(method)) return "기록된 질문에서 다섯 가지 주요 용도에 해당하는 표현을 찾지 못했습니다.";
        return "앞선 대화와 질문을 검토한 모델이 이 질문을 "+purpose.label+" 용도로 분류했습니다.";
    }

    public static View addRubricImpacts(View view,AssessmentModels.Analysis analysis) {
        if (view==null || analysis==null || analysis.indicators()==null) return view;
        var byConversation=new HashMap<Long,List<RubricImpact>>();
        for (var indicator:analysis.indicators()) {
            if (indicator.evidence()==null) continue;
            for (var evidence:indicator.evidence()) {
                if (evidence.conversationId()==null || !"USER".equals(evidence.messageRole())
                        || evidence.type()==null || evidence.reason()==null) continue;
                byConversation.computeIfAbsent(evidence.conversationId(),id->new ArrayList<>())
                        .add(new RubricImpact(indicator.indicator(),evidence.type(),evidence.reason()));
            }
        }
        var questions=view.questions().stream().map(q->new Question(q.conversationId(),q.questionId(),q.quote(),q.purpose(),
                q.votes(),q.haikuPurpose(),q.needsReview(),q.teacherPurpose(),q.method(),q.purposes(),q.weightPerPurpose(),
                q.reason(),q.cues(),getRubricImpacts(byConversation.getOrDefault(q.conversationId(),List.of())))).toList();
        var indexed=new HashMap<Long,Question>();
        questions.forEach(q->indexed.put(q.conversationId(),q));
        var groups=view.usage().stream().map(g->new Group(g.purpose(),g.name(),g.percentage(),g.count(),
                g.representativeQuestions().stream().map(q->indexed.getOrDefault(q.conversationId(),q)).toList())).toList();
        return new View(view.method(),view.status(),view.questionCount(),view.missingRecords(),groups,questions,view.limitations(),
                view.reviewCount(),view.otherCount(),view.undecidedCount(),view.pendingCount());
    }

    private static List<RubricImpact> getRubricImpacts(List<RubricImpact> observed) {
        var impacts=new ArrayList<>(observed);
        for (var criterion:ProcessGradingModels.Criterion.values()) {
            var indicator=AssessmentModels.Indicator.valueOf(criterion.name());
            if (observed.stream().noneMatch(impact->impact.criterion()==indicator)) {
                impacts.add(new RubricImpact(indicator,"NONE","이 질문이 해당 기준에 개별 반영된 근거를 확인할 수 없습니다."));
            }
        }
        return List.copyOf(impacts);
    }

    /** 소수 첫째 자리 비율(1/10%) — 합이 정확히 100%가 되도록 최대 나머지 방식으로 배분합니다. */
    private static Map<Purpose,Integer> percentTenths(Map<Purpose,Integer> counts,int total) {
        var units=new EnumMap<Purpose,Integer>(Purpose.class);if(total==0)return units;int assigned=0;
        for(var e:counts.entrySet()){int n=e.getValue()*1000/total;units.put(e.getKey(),n);assigned+=n;}
        var order=new ArrayList<>(counts.keySet());
        order.sort(Comparator.<Purpose>comparingInt(p->-(counts.get(p)*1000%total)).thenComparingInt(Enum::ordinal));
        for(int i=0;i<1000-assigned;i++)units.merge(order.get(i%order.size()),1,Integer::sum);
        return units;
    }
}
