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
        blocks.add(new Table(List.of(
                List.of("학생",c.nickname()),
                List.of("제출일",formatDate(c.submittedAt())),
                List.of("평가 점수",c.grading()==null?scoreSummary(review):scoreSummary(c.grading()))),
                new int[]{7000,35520},false));
        heading(blocks,"1. 종합 평가");
        int saves=0,warnings=0,completed=0,failed=0;
        for(var e:c.submission().path("learningEvents")){if("ANSWER_SAVED".equals(e.path("type").asText()))saves++;if(WorkModels.ClientEventType.warningNames().contains(e.path("type").asText()))warnings++;}
        for(var t:c.submission().path("conversations")){if("COMPLETED".equals(t.path("state").asText()))completed++;if("FAILED".equals(t.path("state").asText()))failed++;}
        var overview=new ArrayList<List<String>>();
        overview.add(List.of("답안 저장",saves+"회"));overview.add(List.of("AI 질문",c.submission().path("conversations").size()+"건 (완료 "+completed+"건·실패 "+failed+"건)"));overview.add(List.of("경고 신고",warnings+"건"));
        overview.add(List.of("교사 검토",c.grading()!=null?labelCode(c.grading().status()):review==null?"검토 전":labelCode(review.compliance().name())));
        if(review!=null&&!review.comment().isBlank())overview.add(List.of("교사 의견",review.comment()));
        if(c.grading()!=null&&c.grading().summary()!=null)blocks.add(new Text(c.grading().summary(),"body"));
        blocks.add(new Table(overview,new int[]{14520,28000},false));
        blocks.add(new Text("신고 기록만으로 부정행위를 판단하지 않습니다.","note"));
        heading(blocks,"2. 평가 기준별 루브릭");
        var scores=new ArrayList<List<String>>();scores.add(List.of("평가 기준","점수","평가 내용"));
        if(c.grading()!=null) {
            for(var criterion:c.grading().criteria())scores.add(List.of(criterion.name(),criterion.score()==null?"미채점":formatScore(criterion.score())+" / "+criterion.maxScore()+(criterion.teacherScored()?"":" (AI 추천)"),selectFeedback(criterion)));
        }else for(var criterion:Criterion.values()) {
            var rating=review==null?null:review.ratings().stream().filter(r->r.criterion()==criterion).findFirst().orElse(null);
            String evidence=rating==null?"":Objects.toString(rating.evidence(),"");
            scores.add(List.of(ReportPdf.label(criterion),rating==null||rating.score()==null?"미채점":rating.score()+" / "+review.maxScore(),evidence));
        }
        blocks.add(new Table(scores,new int[]{10000,6500,26020},true));
        if(c.grading()!=null&&ProcessGradingModels.RUBRIC_VERSION.equals(c.grading().rubricVersion()))appendProcessDetails(blocks,c.grading(),c.submission());
        heading(blocks,"3. AI 활용 분석");
        var usage=new ArrayList<List<String>>();
        usage.add(List.of("답안 기여도","판단 보류: 저장된 리포트에는 실제 작성 비율이 없습니다."));
        var counts=new LinkedHashMap<String,Integer>();
        for(var cls:c.analysis().classifications())for(String purpose:cls.purposes())counts.merge(labelCode(purpose),1,Integer::sum);
        if(c.grading()!=null&&c.grading().purposeUsage()!=null){
            for(var group:c.grading().purposeUsage().usage())usage.add(List.of(group.name(),group.percentage()+"%"));
            usage.add(List.of("질문 기록 상태",labelCode(c.grading().purposeUsage().status())));
        }else if(counts.isEmpty())usage.add(List.of("AI 사용 기록","기록된 AI 질문이 없습니다."));
        else counts.forEach((name,count)->usage.add(List.of(name,count+"건")));
        if(c.grading()!=null&&c.grading().inputPaths()!=null){
            for(var share:c.grading().inputPaths().shares())usage.add(List.of("입력 경로 · "+switch(share.type()){case DIRECT->"직접 입력";case AI_INSERT->"AI 답변 삽입";case PASTE->"붙여넣기";case UNKNOWN->"확인 불가";},share.percentage()+"% ("+share.characters()+"자)"));
        }
        var disclosure=c.submission().path("aiDisclosure");
        if(!disclosure.isMissingNode()&&!disclosure.isNull()) {
            usage.add(List.of("생성 상태",labelCode(disclosure.path("status").asText("READY"))));
            usage.add(List.of("사용 AI 도구",disclosure.path("tools").asText().isBlank()?"기록된 완료 응답 없음":disclosure.path("tools").asText()));
            usage.add(List.of("사용한 부분",disclosure.path("usedParts").asText().isBlank()?"미작성":disclosure.path("usedParts").asText()));
            usage.add(List.of("선택·활용 이유",disclosure.path("reason").asText().isBlank()?"미작성":disclosure.path("reason").asText()));
        }
        blocks.add(new Table(usage,new int[]{14520,28000},false));
        blocks.add(new Text("키워드 기반 분류(교사 검토 필요). 복수 목적을 허용합니다. 문장 유사도는 실제 작성자 비율이나 기여 점수가 아닙니다.","note"));
        heading(blocks,"4. 활동 및 경고 기록");
        var activities=new ArrayList<List<String>>();activities.add(List.of("시각 (KST)","문항","활동·경고 내용"));
        for(var event:c.submission().path("learningEvents")) {
            String type=event.path("type").asText();if(!"ANSWER_SAVED".equals(type)&&!WorkModels.ClientEventType.warningNames().contains(type))continue;
            String q=questionLabel(c,event.path("questionId").asText());
            String detail="ANSWER_SAVED".equals(type)?"답안 저장 · "+labelCode(event.path("details").path("source").asText()):labelCode(type)+" (학생 기기 신고)";
            activities.add(List.of(formatDate(event.path("createdAt").asText()),q,detail));
        }
        if(activities.size()==1)activities.add(List.of("","","기록된 활동이 없습니다."));
        blocks.add(new Table(activities,new int[]{14520,6500,21500},true));
        heading(blocks,"5. 문항별 제출 답안");
        var answers=new ArrayList<List<String>>();
        answers.add(List.of("문항 번호","답안"));
        for(var a:c.submission().path("answers")) {
            String questionNumber="문항 "+a.path("question").path("position").asInt();
            String answer=ReportPdf.answerText(a.path("answer").path("content").asText()).strip();
            answers.add(List.of(questionNumber,answer.isBlank()?"[미작성]":answer));
        }
        if(answers.size()==1)answers.add(List.of("","제출 답안이 없습니다."));
        blocks.add(new Table(answers,new int[]{6000,36520},true));
        if(raw) {
            blocks.add(new PageBreak());heading(blocks,"부록. AI 대화 기록");int index=0;
            for(var t:c.submission().path("conversations")) {
                blocks.add(new Text("대화 "+(++index)+" · "+questionLabel(c,t.path("questionId").asText()),"question"));
                blocks.add(new Table(List.of(
                    List.of("문항",questionLabel(c,t.path("questionId").asText())),
                    List.of("시각 (KST)",formatDate(t.path("createdAt").asText())),
                    List.of("상태",labelCode(t.path("state").asText())),
                    List.of("질문",t.path("questionText").asText()),
                    List.of("응답",t.path("answerText").isNull()?"[응답 없음]":t.path("answerText").asText())),new int[]{7000,35520},false));
            }
            if(index==0)text(blocks,"기록된 AI 대화가 없습니다.");
        }
        return new Document("AI 활용 학습 보고서","출력일 "+formatDate(report.createdAt())+" (KST)",List.copyOf(blocks));
    }
    private static String selectFeedback(GradingCriterion c){return c.feedback()!=null&&!c.feedback().isBlank()?c.feedback():Objects.toString(c.aiReason(),"");}
    private static String formatScore(double value){return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();}
    private static void appendProcessDetails(List<Block> blocks,GradingSnapshot grading,com.fasterxml.jackson.databind.JsonNode submission){
        var result=grading.aiRecommendation();if(result==null)return;
        if(result.partialScore()!=null){var p=grading.teacherReview()==null?result.partialScore():grading.teacherReview().result().partialScore();if(p!=null)blocks.add(new Text("확인된 항목 "+p.assessedScore()+" / "+p.assessedMaxScore()+"점 · 판단 보류 배점 "+p.heldMaxScore()+"점 · 평가 대상 아님 "+p.notApplicableMaxScore()+"점","note"));}
        for(var cell:result.cells()){
            String questionLabel=ProcessGradingModels.GLOBAL.equals(cell.questionId())?"전체 기록":java.util.stream.StreamSupport.stream(submission.path("answers").spliterator(),false).filter(a->a.path("question").path("id").asText().equals(cell.questionId())).map(a->"문항 "+a.path("question").path("position").asInt()).findFirst().orElse("문항별 평가");
            heading(blocks,cell.criterionName()+" · "+questionLabel);
            var rows=new ArrayList<List<String>>();rows.add(List.of("구분","내용"));
            rows.add(List.of("AI 추천",cell.level()==null?cell.status().equals("NOT_APPLICABLE")?"평가 대상 아님":"판단 보류":cell.level()+"단계 · "+cell.score()+" / "+cell.maxScore()+"점"));
            var teacher=grading.teacherReview();
            var override=teacher==null?null:teacher.overrides().stream().filter(o->o.criterion()==cell.criterion()&&o.questionId().equals(cell.questionId())).findFirst().orElse(null);
            rows.add(List.of("교사 검토",teacher==null?"검토 전":(teacher.confirmed()?"최종 확정":"검토 중")+(override==null?"":": "+(override.score()!=null?override.score().stripTrailingZeros().toPlainString()+" / "+cell.maxScore()+"점 (점수 직접 입력)":override.level()+"단계")+" · "+override.reason())));
            rows.add(List.of("평가 이유",cell.reason()));
            if(cell.details()!=null){
                for(var credit:cell.details().credits())rows.add(List.of("가점 근거 · "+credit.title(),credit.explanation()+"\n"+formatCitations(credit.evidence())));
                for(var deduction:cell.details().deductions())rows.add(List.of("감점 근거 · "+deduction.title(),deduction.explanation()+"\n"+formatCitations(deduction.evidence())));
                for(String note:cell.details().confirmationNeeded())rows.add(List.of("확인 필요",note));
                for(String improvement:cell.details().improvements())rows.add(List.of("개선 방향",improvement));
            }
            for(String limit:cell.limitations())rows.add(List.of("분석 한계",limit));
            blocks.add(new Table(rows,new int[]{12000,30520},false));
        }
    }
    private static String formatCitations(List<ProcessGradingModels.Citation> evidence){return String.join("\n",evidence.stream().map(c->"“"+c.quote()+"”"+(c.location()==null?"":c.location().conversationId()!=null?" (대화 "+c.location().conversationId()+" · "+("USER".equals(c.location().messageRole())?"학생 질문":"AI 응답")+")":" (제출 답안)" )).toList());}
    private static String scoreSummary(GradingSnapshot grading){
        if(ProcessGradingModels.RUBRIC_VERSION.equals(grading.rubricVersion())&&grading.aiRecommendation()!=null){
            var result=grading.teacherReview()==null?grading.aiRecommendation():grading.teacherReview().result();var partial=result.partialScore();
            if(result.status().equals("NOT_APPLICABLE"))return "평가 대상 아님";
            if(partial!=null&&partial.assessedMaxScore().signum()==0&&partial.heldMaxScore().signum()>0)return "판단 보류";
            if(partial!=null)return (grading.teacherReview()!=null&&grading.teacherReview().confirmed()?"교사 확정 ":"확인된 항목 ")+partial.assessedScore()+" / "+partial.assessedMaxScore()+"점"+(partial.heldMaxScore().signum()>0?" (판단 보류 포함)":"");
        }
        return grading.criteria().isEmpty()||grading.criteria().stream().anyMatch(c->!c.teacherScored()||c.score()==null)?"미채점":formatScore(grading.criteria().stream().mapToDouble(GradingCriterion::score).sum())+" / "+grading.criteria().stream().mapToInt(GradingCriterion::maxScore).sum()+"점";}
    private static String scoreSummary(TeacherReview review){return review==null||review.ratings().size()!=5||review.ratings().stream().anyMatch(r->r.score()==null)?"미채점":review.ratings().stream().mapToInt(Rating::score).sum()+" / "+review.maxScore()*5+"점";}
    private static void heading(List<Block> blocks,String text){blocks.add(new Text(text,"heading"));}
    private static void text(List<Block> blocks,String text){if(text!=null&&!text.isBlank())blocks.add(new Text(text,"body"));}
    private static String questionLabel(ReportContent c,String id){for(var a:c.submission().path("answers"))if(id.equals(a.path("question").path("id").asText()))return "문항 "+a.path("question").path("position").asInt();return "평가 전체";}
    static String formatDate(Instant value){return value==null?"미기록":DateTimeFormatter.ofPattern("yyyy. MM. dd HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(value);}
    private static String formatDate(String value){try{return formatDate(Instant.parse(value));}catch(Exception ignored){return "시각 미기록";}}
    private static String labelCode(String code){return switch(code){
        case "READY"->"생성 완료";case "NO_RECORDS"->"질문 기록 없음";case "RECORDS_UNAVAILABLE"->"기록 확보 실패";case "PARTIAL"->"일부 기록 누락";case "HOLD"->"판단 보류";case "DONE"->"AI 분석 완료·교사 검토 전";case "NOT_APPLICABLE"->"평가 대상 아님";case "DRAFT"->"채점 중";case "CONFIRMED"->"채점 확정";case "NOT_STARTED"->"채점 전";case "UNCLASSIFIED"->"미분류";case "NOT_REVIEWED"->"검토 전";case "COMPLIANT"->"규칙 준수";case "NEEDS_REVIEW"->"확인 필요";
        case "COMPLETED"->"완료";case "FAILED"->"실패";case "PENDING"->"대기 중";case "RUNNING"->"처리 중";
        case "DIRECT"->"직접 작성";case "AI_REFERENCED"->"AI 참고";case "AI_USED"->"AI 사용";
        case "WINDOW_LEAVE","WINDOW_BLUR"->"창 이탈";case "SHORTCUT_CAPTURE","CAPTURE_SHORTCUT"->"캡처 단축키";
        case "COPY_ATTEMPT"->"복사 시도";case "FULLSCREEN_EXIT","FULLSCREEN_LEAVE","FULLSCREEN"->"전체화면 이탈";case "DEVTOOLS_OPEN","DEVTOOLS"->"개발자 도구 열림";
        case "IDEATION"->"아이디어 탐색";case "RESEARCH"->"자료 탐색";case "STRUCTURING"->"구조 구성";case "CONCEPT_HELP"->"개념 이해";case "VERIFICATION"->"검증 요청";case "REVISION"->"수정 요청";case "ANSWER_DRAFTING"->"답안 작성 요청";
        default->"기타";};}
}
