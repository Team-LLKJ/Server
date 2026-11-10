package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;
import static kr.lit.api.AssessmentModels.*;

/** 정규화 텍스트 좌표·인용문·붙여넣기 관측값을 계산하는 순수 함수. */
final class AssessmentTrace {
    private AssessmentTrace() {}
    record TextTrace(List<AnswerText> texts,List<Segment> segments) {}
    /** 문항별 답안 텍스트(문단 사이 LF). 문장 판정 구간(segments)은 AssessmentAnalyzer가 같은 좌표로 계산합니다. */
    static TextTrace buildTextTrace(JsonNode source,List<Paragraph> paragraphs) {
        var texts=new ArrayList<AnswerText>();
        for(var a:source.path("answers")) {
            String q=a.path("question").path("id").asText();int version=a.path("answer").path("version").asInt();
            var lines=paragraphs.stream().filter(p->q.equals(p.questionId())).map(Paragraph::text).toList();
            texts.add(new AnswerText(q,String.join("\n",lines),version));
        }
        return new TextTrace(List.copyOf(texts),List.of());
    }
    static List<PasteObservation> getPasteEvents(JsonNode source) {
        var out=new ArrayList<PasteObservation>();
        for(var e:source.path("learningEvents"))if("PASTE".equals(e.path("type").asText())) {
            var d=e.path("details");String q=e.path("questionId").asText(null);Integer start=integer(d,"start"),end=integer(d,"end"),version=integer(d,"answerVersion");boolean matches=false;
            for(var a:source.path("answers"))if(Objects.equals(q,a.path("question").path("id").asText())){String text=String.join("\n",AssessmentAnalyzer.paragraphs(a.path("answer").path("content").asText()));matches=start!=null&&end!=null&&version!=null&&version==a.path("answer").path("version").asInt()&&start>=0&&end>=start&&end<=text.length();}
            out.add(new PasteObservation(e.path("id").asLong(),q,d.path("occurredAt").asText(e.path("createdAt").asText()),integer(d,"length"),start,end,version,matches,d.path("clientReported").asBoolean()));
        }
        return List.copyOf(out);
    }
    private static Integer integer(JsonNode node,String field){return node.hasNonNull(field)?node.path(field).asInt():null;}
    static Evidence quote(JsonNode turn,String role,String type,String reason) {
        String text=turn.path(role.equals("USER")?"questionText":"answerText").asText("");if(text.isBlank())return null;
        int hit=0;var focus=role.equals("USER")?AssessmentAnalyzer.getClassificationPattern(AssessmentAnalyzer.classify(turn)):null;
        if(focus!=null){var matcher=focus.matcher(text);if(matcher.find())hit=matcher.start();}
        int start=hit;while(start>0&&".!?\n".indexOf(text.charAt(start-1))<0)start--;int end=hit;while(end<text.length()&&".!?\n".indexOf(text.charAt(end))<0)end++;if(end<text.length())end++;
        if(end-start>2000){start=Math.max(start,hit-200);end=Math.min(end,start+2000);}if(start>0&&Character.isLowSurrogate(text.charAt(start)))start--;end=Math.min(end,start+2000);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;
        return new Evidence(turn.path("id").asLong(),null,null,reason,type,role,text.substring(start,end),reason);
    }
    static List<Evidence> getEvidence(Indicator indicator,List<JsonNode> turns,List<Sentence> sentences,List<String> appropriate,int specificLength) {
        var evidence=new ArrayList<Evidence>();var seen=new HashSet<String>();var questionCounts=new HashMap<String,Integer>();for(var turn:turns)questionCounts.merge(turn.path("questionId").asText(),1,Integer::sum);boolean hasFollowups=questionCounts.values().stream().anyMatch(count->count>1);
        for(int i=0;i<turns.size()&&evidence.size()<100;i++) {
            var turn=turns.get(i);var type=AssessmentAnalyzer.classify(turn);boolean first=seen.add(turn.path("questionId").asText());Evidence item=null;
            if(indicator==Indicator.PURPOSE)item=quote(turn,"USER",appropriate.contains(type.name())?"CREDIT":"DEDUCTION",appropriate.contains(type.name())?"학습·탐색 목적 분류에 포함된 질문입니다.":type==QuestionType.ANSWER_DRAFTING?"답안 작성 요청으로 분류되어 목적 적절성 산식에 반영됩니다.":"목적 적절성 산식의 학습·탐색 분류에 포함되지 않은 질문입니다.");
            if(indicator==Indicator.SELF_DIRECTION&&(!hasFollowups||!first)){boolean specific=turn.path("questionText").asText().strip().length()>=specificLength||hasFollowups&&Pattern.compile("방금|위에서|앞에서|말한|제안한|네\\s*답|너의\\s*답|그\\s*중|그중|그\\s*부분|이\\s*부분|그렇다면|그러면").matcher(turn.path("questionText").asText()).find();item=quote(turn,"USER",specific&&type!=QuestionType.ANSWER_DRAFTING?"CREDIT":"DEDUCTION","질문 길이·후속 참조에 대한 구체화 규칙의 관측 근거입니다. 실제 사고 수준을 확정하지 않습니다.");}
            if(indicator==Indicator.CRITICAL_REVIEW&&turn.path("state").asText().equals("COMPLETED")) {
                JsonNode follow=null;for(int j=i+1;j<turns.size();j++)if(turn.path("questionId").asText().equals(turns.get(j).path("questionId").asText())){follow=turns.get(j);break;}
                if(follow!=null&&AssessmentAnalyzer.classify(follow)==QuestionType.VERIFICATION)item=quote(follow,"USER","CREDIT","이전 AI 응답에 이어 검증 질문을 보낸 기록입니다.");
                else {boolean changed=sentences.stream().anyMatch(x->AssessmentAnalyzer.REASON_GRAM.equals(x.reason())&&Objects.equals(x.conversationId(),turn.path("id").asLong()));item=quote(turn,"AI",changed?"CREDIT":"DEDUCTION",changed?"이 응답 내용을 고쳐 쓴 것으로 추정되는 문장이 있어 수정 반영 신호로 사용됐습니다.":"이 응답 이후의 검토 신호가 관측되지 않았습니다. 응답 자체의 오류를 뜻하지 않습니다.");}
            }
            if(indicator==Indicator.TRANSPARENCY)item=quote(turn,"USER","CREDIT","서버에 저장된 질문 기록으로 투명성의 대화 기록 항목을 확인합니다.");
            if(item!=null)evidence.add(item);
        }
        return List.copyOf(evidence);
    }
}
