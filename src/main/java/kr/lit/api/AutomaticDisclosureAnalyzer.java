package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.WorkModels.*;

/**
 * AI 활용 기록(사용 도구 / 활용 부분 / 이유)을 서버가 자동으로 만듭니다(2026-11-14). 점수에는 쓰지 않고 교사 화면·보고서에만 보여 줍니다.
 * 활용 부분과 이유는 답안 문장 판정(AssessmentAnalyzer: 글자 비교 → LLM 표현 판정 → 교사 수정)으로 만듭니다.
 * 기록된 대화는 관측 사실이며 작성자나 학생 의도를 확정하지 않습니다.
 */
public class AutomaticDisclosureAnalyzer {
    static final String NO_AI_SENTENCE="답안에 AI 답변을 그대로 쓴 문장 없음";
    static final String PENDING_PARTS="답안 문장 분석 중입니다. 분석이 끝나면 자동으로 채웁니다.";
    private static final int QUOTE=30;

    /** 제출 시점(문장 분석 전). 활용 부분·이유는 분석이 끝나면 AutomaticDisclosureService.refreshUsedParts가 채웁니다. */
    public DisclosureView generateDisclosure(String id,String pid,JsonNode source,Instant now){return generateDisclosure(id,pid,source,now,null,null);}

    /**
     * sentences: 그 제출본의 문장 판정(null이면 분석 전). semanticStatus: 표현 판정 상태(PENDING이면 이유에 대기 중이라고 적습니다).
     */
    public DisclosureView generateDisclosure(String id,String pid,JsonNode source,Instant now,List<AssessmentModels.Sentence> sentences,String semanticStatus){
        var tools=new TreeSet<String>();var ids=new ArrayList<Long>();var evidence=new ArrayList<DisclosureEvidence>();
        var answerVersions=new HashMap<String,Integer>();var positions=new LinkedHashMap<String,Integer>();var questionOf=new HashMap<Long,String>();
        for(var a:source.path("answers")){
            String q=a.path("question").path("id").asText();positions.put(q,a.path("question").path("position").asInt());
            answerVersions.put(q,a.path("answer").path("version").asInt());
        }
        var aiParagraphs=new HashMap<Long,TreeSet<Integer>>();
        var aiSentences=new ArrayList<AssessmentModels.Sentence>();var studentFirst=new ArrayList<AssessmentModels.Sentence>();
        for(var turn:source.path("conversations"))questionOf.put(turn.path("id").asLong(),turn.path("questionId").asText());
        if(sentences!=null)for(var s:sentences){
            if(AssessmentAnalyzer.SENTENCE_AI.equals(s.source())){
                aiSentences.add(s);
                if(s.conversationId()!=null&&s.paragraphId()!=null&&s.questionId().equals(questionOf.get(s.conversationId()))){
                    int at=s.paragraphId().lastIndexOf("-p");
                    if(at>=0)try{aiParagraphs.computeIfAbsent(s.conversationId(),k->new TreeSet<>()).add(Integer.parseInt(s.paragraphId().substring(at+2)));}catch(NumberFormatException ignored){}
                }
            }else if(s.studentFirstConversationId()!=null)studentFirst.add(s);
        }
        int completed=0,failed=0;
        for(var turn:source.path("conversations")){
            long cid=turn.path("id").asLong();if(cid<=0)throw new IllegalArgumentException("Missing conversation identifier");ids.add(cid);
            String qid=turn.path("questionId").asText();
            boolean usable="COMPLETED".equals(turn.path("state").asText())&&!turn.path("answerText").asText().isBlank();
            if(usable){completed++;tools.add("LIT 내장 AI ("+turn.path("provider").asText("미기록")+" / "+turn.path("model").asText("미기록")+")");}else failed++;
            String purpose=switch(AssessmentAnalyzer.classify(turn).name()){
                case "CONCEPT_UNDERSTANDING"->"개념 확인";case "RESEARCH"->"자료 탐색";case "IDEA_EXPLORATION"->"아이디어 구체화";
                case "ANSWER_DRAFTING"->"답안 작성 요청";case "VERIFICATION"->"검증 요청";case "EDIT"->"표현 수정 요청";default->"기타 요청";};
            evidence.add(new DisclosureEvidence(cid,qid,purpose,"USER",turn.path("questionText").asText(),List.copyOf(aiParagraphs.getOrDefault(cid,new TreeSet<>())),answerVersions.getOrDefault(qid,0)));
        }
        if(ids.isEmpty())return new DisclosureView(id,pid,"","LIT에 기록된 AI 대화가 없습니다.","외부 AI 사용 여부는 확인할 수 없습니다.",now,false,"READY","SYSTEM_GENERATED",null,null,List.of(),List.of());
        String used,reason;
        if(sentences==null){used=PENDING_PARTS;reason=PENDING_PARTS;}
        else{
            var order=Comparator.<AssessmentModels.Sentence>comparingInt(s->positions.getOrDefault(s.questionId(),Integer.MAX_VALUE)).thenComparingInt(AssessmentModels.Sentence::start);
            aiSentences.sort(order);studentFirst.sort(order);
            var usedLines=new ArrayList<String>();
            if(aiSentences.isEmpty())usedLines.add(NO_AI_SENTENCE);
            else for(var q:positions.entrySet()){
                var list=aiSentences.stream().filter(s->s.questionId().equals(q.getKey())).toList();
                usedLines.add("문항 "+q.getValue()+": "+(list.isEmpty()?NO_AI_SENTENCE:String.join(", ",list.stream().map(s->quote(s)+" ("+answer(s.conversationId())+")").toList())));
            }
            var reasonLines=new ArrayList<String>();
            for(var s:aiSentences)reasonLines.add(location(s,positions)+": "+aiReason(s));
            for(var s:studentFirst)reasonLines.add(location(s,positions)+": "+answer(s.studentFirstConversationId())+"에도 있지만 학생이 질문에서 먼저 쓴 표현이라 학생 문장으로 봄");
            if(reasonLines.isEmpty())reasonLines.add("AI 답변과 같거나 표현을 바꿔 쓴 문장이 없습니다.");
            if(SemanticJudge.STATUS_PENDING.equals(semanticStatus))reasonLines.add("AI 답변의 표현을 바꿔 썼는지 판정하는 중입니다. 끝나면 다시 채웁니다.");
            used=String.join("\n",usedLines);reason=String.join("\n",reasonLines);
        }
        return new DisclosureView(id,pid,String.join("\n",tools),used,reason,now,false,"READY","SYSTEM_GENERATED",null,null,List.copyOf(ids),List.copyOf(evidence));
    }
    static String aiReason(AssessmentModels.Sentence s){
        String a=answer(s.conversationId());
        String text=switch(String.valueOf(s.reason())){
            case AssessmentAnalyzer.REASON_SPAN->a+with(s.conversationId())+" "+s.longestSpan()+"자 이상 같음";
            case AssessmentAnalyzer.REASON_GRAM->a+with(s.conversationId())+" 표현이 "+Math.round(s.similarity()*100)+"% 겹침(3글자 단위 비교)";
            case AssessmentAnalyzer.REASON_SEMANTIC->a+"의 표현을 바꿔 썼다고 판정("+(s.semantic()!=null&&s.semantic().geminiLevel()!=null?"Haiku·Gemini":"Haiku")+")";
            case AssessmentAnalyzer.REASON_TEACHER->"교사가 AI 답변을 쓴 문장으로 확정"+(s.conversationId()==null?"":" ("+a+")");
            default->"AI 답변을 쓴 문장"+(s.conversationId()==null?"":" ("+a+")");
        };
        if(s.teacherLabel()!=null&&!AssessmentAnalyzer.REASON_TEACHER.equals(s.reason()))text+=" · 교사 확인함";
        else if(s.needsReview())text+=" · 교사 확인 필요";
        return text;
    }
    private static String location(AssessmentModels.Sentence s,Map<String,Integer> positions){
        return "문항 "+(positions.containsKey(s.questionId())?positions.get(s.questionId()):s.questionId())+" "+quote(s);
    }
    static String quote(AssessmentModels.Sentence s){
        String t=s.text()==null?"":s.text().strip().replaceAll("\\s+"," ");
        return "'"+(t.codePointCount(0,t.length())>QUOTE?t.substring(0,t.offsetByCodePoints(0,QUOTE))+"…":t)+"'";
    }
    private static String answer(Long cid){return cid==null?"AI 답변":"AI 답변 #"+cid;}
    /** 숫자 끝소리에 맞는 조사(와/과). id가 없으면 "AI 답변"에 맞춰 "과". */
    private static String with(Long cid){
        if(cid==null)return "과";
        long last=Math.abs(cid%10);
        return last==2||last==4||last==5||last==9?"와":"과";
    }
}
