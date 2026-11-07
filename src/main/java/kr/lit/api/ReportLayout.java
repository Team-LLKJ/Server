package kr.lit.api;

import org.springframework.stereotype.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static kr.lit.api.ReportModels.*;

/** PDF/HWP 공통 표현 모델. 저장된 리포트만 사용하며 DB·시계에 접근하지 않습니다. */
@Component
public class ReportLayout {
    public sealed interface Block permits Text,Table,PageBreak {}
    public record Text(String value,String style) implements Block {}
    public record Table(List<List<String>> rows,int[] widths,boolean header) implements Block {}
    public record PageBreak() implements Block {}
    public record Document(String title,String date,List<Block> blocks) {}
    public Document buildDocument(UsageReport report,boolean raw) {
        var c=report.content();var blocks=new ArrayList<Block>();var review=c.teacherReview();
        blocks.add(new Text(c.title(),"subtitle"));
        blocks.add(new Table(List.of(List.of("학생",c.nickname(),"제출 상태",c.submittedAt()==null?"미제출":"제출완료"),
                List.of("제출일",formatDate(c.submittedAt()),"평가 점수",c.grading()==null?scoreSummary(review):scoreSummary(c.grading()))),new int[]{7000,14260,7000,14260},false));
        heading(blocks,"1. 종합 평가");
        int saves=0,warnings=0,completed=0,failed=0;
        for(var e:c.submission().path("learningEvents")){if("ANSWER_SAVED".equals(e.path("type").asText()))saves++;if(WorkModels.ClientEventType.warningNames().contains(e.path("type").asText()))warnings++;}
        for(var t:c.submission().path("conversations")){if("COMPLETED".equals(t.path("state").asText()))completed++;if("FAILED".equals(t.path("state").asText()))failed++;}
        text(blocks,"저장 "+saves+"회, AI 질문 "+c.submission().path("conversations").size()+"건(완료 "+completed+"건·실패 "+failed+"건), 경고 신고 "+warnings+"건입니다. 신고 기록만으로 부정행위를 판단하지 않습니다.");
        if(review!=null&&!review.comment().isBlank())text(blocks,review.comment());
        text(blocks,"교사 검토: "+(c.grading()!=null?labelCode(c.grading().status()):review==null?"검토 전":labelCode(review.compliance().name())));
        heading(blocks,"2. 평가 기준별 루브릭");
        var scores=new ArrayList<List<String>>();scores.add(List.of("평가 기준","점수","평가 내용"));
        if(c.grading()!=null) {
            for(var criterion:c.grading().criteria())scores.add(List.of(criterion.name(),criterion.score()==null?"미채점":formatScore(criterion.score())+" / "+criterion.maxScore()+(criterion.teacherScored()?"":" (AI 추천)"),shorten(selectFeedback(criterion),100)));
        }else for(var criterion:Criterion.values()) {
            var rating=review==null?null:review.ratings().stream().filter(r->r.criterion()==criterion).findFirst().orElse(null);
            String evidence=rating==null?"":Objects.toString(rating.evidence(),"");
            scores.add(List.of(ReportPdf.label(criterion),rating==null||rating.score()==null?"미채점":rating.score()+" / "+review.maxScore(),shorten(evidence,100)));
        }
        blocks.add(new Table(scores,new int[]{14520,8000,20000},true));
        if(c.grading()!=null) {
            for(var criterion:c.grading().criteria())if(selectFeedback(criterion).length()>100)text(blocks,criterion.name()+" 평가 내용: "+selectFeedback(criterion));
        }else if(review!=null)for(var rating:review.ratings())if(rating.evidence()!=null&&rating.evidence().length()>100)text(blocks,ReportPdf.label(rating.criterion())+" 평가 내용: "+rating.evidence());
        heading(blocks,"3. AI 활용 분석");
        var usage=new ArrayList<List<String>>();
        usage.add(List.of("답안 기여도","판단 보류: 저장된 리포트에는 실제 작성 비율이 없습니다."));
        var counts=new LinkedHashMap<String,Integer>();
        for(var cls:c.analysis().classifications())for(String purpose:cls.purposes())counts.merge(labelCode(purpose),1,Integer::sum);
        if(counts.isEmpty())usage.add(List.of("AI 사용 기록","기록된 AI 질문이 없습니다."));
        else counts.forEach((name,count)->usage.add(List.of(name,count+"건")));
        blocks.add(new Table(usage,new int[]{14520,28000},false));
        blocks.add(new Text("키워드 기반 분류(교사 검토 필요). 복수 목적을 허용합니다. 문장 유사도는 실제 작성자 비율이나 기여 점수가 아닙니다.","note"));
        heading(blocks,"4. 활동 및 경고 기록");
        int eventNumber=0;
        for(var event:c.submission().path("learningEvents")) {
            String type=event.path("type").asText();if(!"ANSWER_SAVED".equals(type)&&!WorkModels.ClientEventType.warningNames().contains(type))continue;
            eventNumber++;String q=questionLabel(c,event.path("questionId").asText());
            String detail="ANSWER_SAVED".equals(type)?"답안 저장 · "+labelCode(event.path("details").path("source").asText()):labelCode(type)+" (학생 기기 신고)";
            text(blocks,formatDate(event.path("createdAt").asText())+" | "+q+" | "+detail);
        }
        if(eventNumber==0)text(blocks,"기록된 활동이 없습니다.");
        heading(blocks,"5. 문항별 제출 답안");
        for(var a:c.submission().path("answers")) {
            blocks.add(new Text("문항 "+a.path("question").path("position").asInt()+" · "+a.path("question").path("title").asText(),"question"));
            String problem=ReportPdf.answerText(a.path("question").path("content").asText());
            if(!problem.isBlank())text(blocks,"문제: "+problem);
            String answer=ReportPdf.answerText(a.path("answer").path("content").asText());
            text(blocks,"답안: "+(answer.isBlank()?"[미작성]":answer));
        }
        if(raw) {
            blocks.add(new PageBreak());heading(blocks,"부록. AI 대화 기록");int index=0;
            for(var t:c.submission().path("conversations")) {
                blocks.add(new Text("대화 "+(++index)+" · "+questionLabel(c,t.path("questionId").asText()),"question"));
                blocks.add(new Text(formatDate(t.path("createdAt").asText())+" · "+labelCode(t.path("state").asText()),"note"));
                text(blocks,"질문: "+t.path("questionText").asText());
                text(blocks,"응답: "+(t.path("answerText").isNull()?"[응답 없음]":t.path("answerText").asText()));
            }
            if(index==0)text(blocks,"기록된 AI 대화가 없습니다.");
        }
        return new Document("AI 활용 학습 보고서","출력일 "+formatDate(report.createdAt())+" (KST)",List.copyOf(blocks));
    }
    private static String selectFeedback(GradingCriterion c){return c.feedback()!=null&&!c.feedback().isBlank()?c.feedback():Objects.toString(c.aiReason(),"");}
    private static String formatScore(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private static String scoreSummary(GradingSnapshot grading){return grading.criteria().isEmpty()||grading.criteria().stream().anyMatch(c->!c.teacherScored()||c.score()==null)?"미채점":formatScore(grading.criteria().stream().mapToDouble(GradingCriterion::score).sum())+" / "+grading.criteria().stream().mapToInt(GradingCriterion::maxScore).sum()+"점";}
    private static String scoreSummary(TeacherReview review){return review==null||review.ratings().size()!=5||review.ratings().stream().anyMatch(r->r.score()==null)?"미채점":review.ratings().stream().mapToInt(Rating::score).sum()+" / "+review.maxScore()*5+"점";}
    private static void heading(List<Block> blocks,String text){blocks.add(new Text(text,"heading"));}
    private static void text(List<Block> blocks,String text){if(text!=null&&!text.isBlank())blocks.add(new Text(text,"body"));}
    private static String shorten(String text,int max){return text.length()<=max?text:text.substring(0,max)+"… (상세 내용 아래)";}
    private static String questionLabel(ReportContent c,String id){for(var a:c.submission().path("answers"))if(id.equals(a.path("question").path("id").asText()))return "문항 "+a.path("question").path("position").asInt();return "평가 전체";}
    static String formatDate(Instant value){return value==null?"미기록":DateTimeFormatter.ofPattern("yyyy. MM. dd HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(value);}
    private static String formatDate(String value){try{return formatDate(Instant.parse(value));}catch(Exception ignored){return "시각 미기록";}}
    private static String labelCode(String code){return switch(code){
        case "DRAFT"->"채점 중";case "CONFIRMED"->"채점 확정";case "NOT_STARTED"->"채점 전";case "UNCLASSIFIED"->"미분류";case "NOT_REVIEWED"->"검토 전";case "COMPLIANT"->"규칙 준수";case "NEEDS_REVIEW"->"확인 필요";
        case "COMPLETED"->"완료";case "FAILED"->"실패";case "PENDING"->"대기 중";case "RUNNING"->"처리 중";
        case "DIRECT"->"직접 작성";case "AI_REFERENCED"->"AI 참고";case "AI_USED"->"AI 사용";
        case "WINDOW_LEAVE","WINDOW_BLUR"->"창 이탈";case "SHORTCUT_CAPTURE","CAPTURE_SHORTCUT"->"캡처 단축키";
        case "COPY_ATTEMPT"->"복사 시도";case "FULLSCREEN_EXIT","FULLSCREEN_LEAVE"->"전체화면 이탈";case "DEVTOOLS_OPEN"->"개발자 도구 열림";
        case "IDEATION"->"아이디어 탐색";case "RESEARCH"->"자료 탐색";case "STRUCTURING"->"구조 구성";case "CONCEPT_HELP"->"개념 이해";case "VERIFICATION"->"검증 요청";case "REVISION"->"수정 요청";case "ANSWER_DRAFTING"->"답안 작성 요청";
        default->"기타";};}
}
