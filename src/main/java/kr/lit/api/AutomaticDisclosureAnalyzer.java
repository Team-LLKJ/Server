package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;
import static kr.lit.api.WorkModels.*;

/**
 * AI 활용 기록(사용 도구 / 활용 부분 / 이유)을 서버가 자동으로 만듭니다(2026-11-14). 점수에는 쓰지 않고 교사 화면·보고서에만 보여 줍니다.
 * 활용 부분은 질문 용도 분류, 이유는 답안 문장 판정으로 만듭니다.
 * 기록된 대화는 관측 사실이며 작성자나 학생 의도를 확정하지 않습니다.
 */
public class AutomaticDisclosureAnalyzer {
    static final String PENDING_PARTS="답안 문장 분석 중입니다. 분석이 끝나면 자동으로 채웁니다.";
    private static final int QUOTE=30;

    /** 제출 시점에는 질문별 활용 부분을 만들고 문장 분석을 기다립니다. */
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
        var usedLines=new ArrayList<String>();
        for(var turn:source.path("conversations"))questionOf.put(turn.path("id").asLong(),turn.path("questionId").asText());
        if(sentences!=null)for(var s:sentences){
            if(AssessmentAnalyzer.SENTENCE_AI.equals(s.source())){
                if(s.conversationId()!=null&&s.paragraphId()!=null&&s.questionId().equals(questionOf.get(s.conversationId()))){
                    int at=s.paragraphId().lastIndexOf("-p");
                    if(at>=0)try{aiParagraphs.computeIfAbsent(s.conversationId(),k->new TreeSet<>()).add(Integer.parseInt(s.paragraphId().substring(at+2)));}catch(NumberFormatException ignored){}
                }
            }
        }
        var purposes=new HashMap<Long,PurposeUsageAnalyzer.Question>();
        new PurposeUsageAnalyzer().analyzeUsage(source).questions().forEach(q->purposes.put(q.conversationId(),q));
        for(var turn:source.path("conversations")){
            long cid=turn.path("id").asLong();if(cid<=0)throw new IllegalArgumentException("Missing conversation identifier");ids.add(cid);
            String qid=turn.path("questionId").asText();
            boolean usable="COMPLETED".equals(turn.path("state").asText())&&!turn.path("answerText").asText().isBlank();
            if(usable)tools.add("LIT 내장 AI ("+turn.path("provider").asText("미기록")+" / "+turn.path("model").asText("미기록")+")");
            var classification=purposes.get(cid);
            String purpose=classification==null?"기타":classification.purpose().label;
            usedLines.add("문항 "+positions.getOrDefault(qid,0)+" – "+purpose+": "+quote(turn.path("questionText").asText(),Integer.MAX_VALUE));
            evidence.add(new DisclosureEvidence(cid,qid,purpose,"USER",turn.path("questionText").asText(),List.copyOf(aiParagraphs.getOrDefault(cid,new TreeSet<>())),answerVersions.getOrDefault(qid,0)));
        }
        if(ids.isEmpty()&&sentences==null)return new DisclosureView(id,pid,"","LIT에 기록된 AI 대화가 없습니다.","외부 AI 사용 여부는 확인할 수 없습니다.",now,false,"READY","SYSTEM_GENERATED",null,null,List.of(),List.of());
        String used=usedLines.isEmpty()?"LIT에 기록된 AI 대화가 없습니다.":String.join("\n",usedLines);
        String reason=PENDING_PARTS;
        if(sentences!=null){
            var order=Comparator.<AssessmentModels.Sentence>comparingInt(s->positions.getOrDefault(s.questionId(),Integer.MAX_VALUE))
                    .thenComparingInt(AssessmentModels.Sentence::start);
            var sentenceNumbers=new HashMap<String,Integer>();
            var reasonLines=new ArrayList<String>();
            for(var sentence:sentences.stream().sorted(order).toList()){
                int number=sentenceNumbers.merge(sentence.questionId(),1,Integer::sum);
                String location="문항 "+positions.getOrDefault(sentence.questionId(),0)+" 답안 "+number+"번째 문장";
                reasonLines.add(location+" – "+getSentenceReason(sentence));
            }
            if(reasonLines.isEmpty())reasonLines.add("분석할 답안 문장이 없습니다.");
            if(SemanticJudge.STATUS_PENDING.equals(semanticStatus))reasonLines.add("AI 답변의 표현을 바꿔 썼는지 판정하는 중입니다. 끝나면 다시 채웁니다.");
            reason=String.join("\n",reasonLines);
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
    private static String getSentenceReason(AssessmentModels.Sentence sentence){
        if(AssessmentAnalyzer.SENTENCE_AI.equals(sentence.source())){
            var match=sentence.aiAnswerMatch();
            String excerpt=match!=null?match.text():null;
            if(excerpt==null&&sentence.semantic()!=null)excerpt=sentence.semantic().closestCandidateText();
            return "AI 답변 인용"+(excerpt==null||excerpt.isBlank()?"": " "+quote(excerpt,QUOTE))+": "+aiReason(sentence);
        }
        if(sentence.teacherLabel()!=null)return "교사가 학생 문장으로 확정";
        if(sentence.studentFirstConversationId()!=null)return answer(sentence.studentFirstConversationId())+"에도 있지만 학생이 질문에서 먼저 쓴 표현이라 학생 문장으로 봄";
        if(sentence.needsReview())return "문장 출처를 교사가 확인해야 합니다.";
        return "학생 문장으로 분류";
    }
    private static String quote(String text,int limit){
        String t=text==null?"":text.strip().replaceAll("\\s+"," ");
        return "\""+(t.codePointCount(0,t.length())>limit?t.substring(0,t.offsetByCodePoints(0,limit))+"…":t)+"\"";
    }
    private static String answer(Long cid){return cid==null?"AI 답변":"AI 답변 #"+cid;}
    /** 숫자 끝소리에 맞는 조사(와/과). id가 없으면 "AI 답변"에 맞춰 "과". */
    private static String with(Long cid){
        if(cid==null)return "과";
        long last=Math.abs(cid%10);
        return last==2||last==4||last==5||last==9?"와":"과";
    }
}
