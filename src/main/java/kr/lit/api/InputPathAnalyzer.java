package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static kr.lit.api.WorkModels.*;

/** 입력 이력을 재생하는 순수 계산. 직접 입력은 저자 신원을 의미하지 않습니다. */
public class InputPathAnalyzer {
    public enum PathType { DIRECT, AI_INSERT, PASTE, UNKNOWN }
    public record Segment(int start,int end,PathType type) {}
    public record Share(PathType type,String colorKey,long characters,double percentage) {}
    public record QuestionPath(String questionId,int answerVersion,String text,String status,List<Segment> segments,List<Share> shares,int nonTextElements,boolean clientReported) {}
    public record View(String method,String status,List<QuestionPath> questions,List<Share> shares,List<String> limitations) {}
    public static String normalizeText(String html){return String.join("\n",AssessmentAnalyzer.paragraphs(html));}
    public static boolean validBoundary(String text,int index){return index>=0&&index<=text.length()&&!(index>0&&index<text.length()&&Character.isHighSurrogate(text.charAt(index-1))&&Character.isLowSurrogate(text.charAt(index)));}
    public static String replayText(String original,List<InputOperation> operations){
        String current=original;
        if(operations==null)return null;
        if(operations.size()>500||operations.stream().filter(Objects::nonNull).mapToLong(op->op.text()==null?0:op.text().length()).sum()>1000000)throw new IllegalArgumentException("INPUT_TRACE_TOO_LARGE");
        for(var operation:operations){
            if(operation==null||operation.type()==null||operation.text()==null||!validBoundary(current,operation.start())||!validBoundary(current,operation.end())||operation.start()>operation.end())throw new IllegalArgumentException("INVALID_INPUT_OPERATION");
            for(int i=0;i<operation.text().length();i++){
                char c=operation.text().charAt(i);
                if(Character.isHighSurrogate(c)){if(i+1>=operation.text().length()||!Character.isLowSurrogate(operation.text().charAt(++i)))throw new IllegalArgumentException("INVALID_INPUT_OPERATION");}
                else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("INVALID_INPUT_OPERATION");
            }
            if(operation.type()==InputOperationType.FORMAT){if(!operation.text().isEmpty())throw new IllegalArgumentException("INVALID_INPUT_OPERATION");continue;}
            if(operation.type()==InputOperationType.DELETE&&!operation.text().isEmpty())throw new IllegalArgumentException("INVALID_INPUT_OPERATION");
            current=current.substring(0,operation.start())+operation.text()+current.substring(operation.end());
            if(current.length()>1000000)throw new IllegalArgumentException("INPUT_TRACE_TOO_LARGE");
        }
        return current;
    }
    public View analyzePaths(JsonNode snapshot){
        var questions=new ArrayList<QuestionPath>();
        for(var entry:snapshot.path("answers")){
            String qid=entry.path("question").path("id").asText();var answer=entry.path("answer");int version=answer.path("version").asInt();
            String target=normalizeText(answer.path("content").asText());String current="";var labels=new ArrayList<PathType>();int currentVersion=0;
            for(var event:snapshot.path("learningEvents")){
                if(!qid.equals(event.path("questionId").asText())||!"ANSWER_SAVED".equals(event.path("type").asText()))continue;
                var details=event.path("details");int nextVersion=details.path("version").asInt();if(nextVersion>version)continue;
                String next=normalizeText(details.path("content").asText());
                boolean linked=currentVersion==details.path("previousVersion").asInt()&&current.equals(normalizeText(details.path("previousContent").asText()));
                if(linked&&details.path("inputOperations").isArray()){
                    try{
                        var operations=new ArrayList<InputOperation>();
                        for(var op:details.path("inputOperations"))operations.add(new InputOperation(InputOperationType.valueOf(op.path("type").asText()),op.path("start").asInt(-1),op.path("end").asInt(-1),op.path("text").asText(null),op.hasNonNull("conversationId")?op.path("conversationId").asLong():null));
                        if(!next.equals(replayText(current,operations)))throw new IllegalArgumentException("INPUT_TRACE_MISMATCH");
                        for(var op:operations)if(op.type()!=InputOperationType.FORMAT){
                            labels.subList(op.start(),op.end()).clear();
                            PathType type=switch(op.type()){case DIRECT->PathType.DIRECT;case PASTE->PathType.PASTE;case AI_INSERT->PathType.AI_INSERT;default->PathType.UNKNOWN;};
                            labels.addAll(op.start(),Collections.nCopies(op.text().length(),type));
                        }
                    }catch(RuntimeException invalid){labels=new ArrayList<>(Collections.nCopies(next.length(),PathType.UNKNOWN));}
                }else if(!linked||!current.equals(next))labels=new ArrayList<>(Collections.nCopies(next.length(),PathType.UNKNOWN));
                current=next;currentVersion=nextVersion;
            }
            if(currentVersion!=version||!current.equals(target)||labels.size()!=target.length())labels=new ArrayList<>(Collections.nCopies(target.length(),PathType.UNKNOWN));
            var segments=new ArrayList<Segment>();int start=-1;PathType type=null;
            for(int i=0;i<target.length();){int length=Character.charCount(target.codePointAt(i));boolean space=Character.isWhitespace(target.codePointAt(i))||Character.isSpaceChar(target.codePointAt(i));
                if(space){if(start>=0){segments.add(new Segment(start,i,type));start=-1;type=null;}}
                else if(start<0){start=i;type=labels.get(i);}else if(type!=labels.get(i)){segments.add(new Segment(start,i,type));start=i;type=labels.get(i);}
                i+=length;
            }
            if(start>=0)segments.add(new Segment(start,target.length(),type));
            var counts=countCharacters(target,segments);boolean unknown=counts.get(PathType.UNKNOWN)>0;
            int elements=org.jsoup.Jsoup.parseBodyFragment(answer.path("content").asText()).select("img,video,audio,iframe,svg,canvas,hr").size();
            questions.add(new QuestionPath(qid,version,target,unknown?"PARTIAL":"COMPLETE",List.copyOf(segments),calculateShares(counts),elements,true));
        }
        var totals=new EnumMap<PathType,Long>(PathType.class);for(var t:PathType.values())totals.put(t,0L);
        for(var q:questions)for(var s:q.shares())totals.merge(s.type(),s.characters(),Long::sum);
        return new View("EDIT_EVENT_REPLAY_V3",questions.stream().anyMatch(q->q.status().equals("PARTIAL"))?"PARTIAL":"COMPLETE",List.copyOf(questions),calculateShares(totals),List.of("학생 기기의 입력 경로 신고이며 실제 작성자·외부 출처를 증명하지 않습니다.","공백·줄바꿈·삭제된 글자는 분모에서 제외합니다. 구간 위치는 text의 UTF-16·end 제외입니다.","이력이 없거나 버전이 이어지지 않는 부분은 확인 불가이며 유사도로 채우지 않습니다."));
    }
    private Map<PathType,Long> countCharacters(String text,List<Segment> segments){var counts=new EnumMap<PathType,Long>(PathType.class);for(var t:PathType.values())counts.put(t,0L);for(var s:segments)counts.merge(s.type(),(long)text.codePointCount(s.start(),s.end()),Long::sum);return counts;}
    private List<Share> calculateShares(Map<PathType,Long> counts){
        long total=counts.values().stream().mapToLong(Long::longValue).sum();var tenths=new EnumMap<PathType,Integer>(PathType.class);var remainders=new ArrayList<PathType>();int allocated=0;
        for(var t:PathType.values()){int n=total==0?0:(int)(counts.getOrDefault(t,0L)*1000/total);tenths.put(t,n);allocated+=n;remainders.add(t);}
        if(total>0){remainders.sort(Comparator.<PathType>comparingLong(t->-(counts.getOrDefault(t,0L)*1000%total)).thenComparingInt(Enum::ordinal));for(int i=0;i<1000-allocated;i++)tenths.merge(remainders.get(i),1,Integer::sum);}
        return Arrays.stream(PathType.values()).map(t->new Share(t,switch(t){case DIRECT->"blue";case AI_INSERT->"orange";case PASTE->"yellow";case UNKNOWN->"gray";},counts.getOrDefault(t,0L),tenths.get(t)/10.0)).toList();
    }
}
