package kr.lit.api;

import com.fasterxml.jackson.databind.JsonNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import static kr.lit.api.AssessmentModels.*;

/**
 * 7-4 채점 알고리즘. 제출 snapshot(답안·AI 대화·학습 이벤트)과 학생의 AI 사용 기록을 입력으로 받아
 * 허용 범위 검사 → 질문 분류 → 문단 출처 판정 → 지표(0~1) → 수준(round(4×s))을 계산합니다.
 * DB에 접근하지 않는 순수 계산이므로 같은 입력은 항상 같은 결과를 냅니다.
 */
@Component
public class AssessmentAnalyzer {
    public static final String MODEL="LIT_RULE_BASED_RUBRIC_V1";
    public record DisclosureInput(String tools,String usedParts,String reason) {
        static final DisclosureInput EMPTY=new DisclosureInput("","","");
    }
    private final GradingConfig config;
    public AssessmentAnalyzer(GradingConfig config) { this.config=config; }

    // ---------- 질문 분류 (규칙 기반; 우선순위 순서대로 첫 일치) ----------
    private static final List<Map.Entry<QuestionType,Pattern>> RULES=List.of(
        Map.entry(QuestionType.ANSWER_DRAFTING,Pattern.compile("(답안|정답|대신|작성해|써\\s*줘|써줘|풀어\\s*줘|완성해|만들어\\s*줘|답\\s*을?\\s*알려|전체\\s*답|그대로\\s*쓸|write\\s+(it|my|the)|solve\\s+it)")),
        Map.entry(QuestionType.VERIFICATION,Pattern.compile("(맞아|맞니|맞는지|맞나|맞습니까|검토|확인해|검증|오류|틀린|틀렸|출처|근거|사실인지|정확한지|반례|다른\\s*관점|비교해|반박|동의하지|의심)")),
        Map.entry(QuestionType.RESEARCH,Pattern.compile("(자료|찾아|검색|조사|예시|사례|통계|데이터|어디서|참고\\s*문헌|논문)")),
        Map.entry(QuestionType.IDEA_EXPLORATION,Pattern.compile("(아이디어|브레인|발상|방향|구체화|어떻게\\s*하면|주제|구조|개요|목차|구성|접근)")),
        Map.entry(QuestionType.CONCEPT_UNDERSTANDING,Pattern.compile("(설명|개념|뜻|의미|이해|왜|원리|차이|무엇|뭐야|뭔가요|뭔지|힌트|어떤\\s*것|정의)"))
    );
    public static QuestionType classify(String text) {
        String t=text==null?"":text.toLowerCase(Locale.ROOT);
        for(var rule:RULES) if(rule.getValue().matcher(t).find()) return rule.getKey();
        return QuestionType.OTHER;
    }
    private static final Pattern FOLLOW_UP_REFERENCE=Pattern.compile("(방금|위에서|앞에서|말한|제안한|네\\s*답|너의\\s*답|그\\s*중|그중|그\\s*부분|이\\s*부분|그렇다면|그러면)");

    // ---------- 문단 추출 ----------
    record RawParagraph(String text) {}
    static List<String> paragraphs(String html) {
        if(html==null||html.isBlank()) return List.of();
        var out=new ArrayList<String>();
        if(!html.matches("(?s).*<\\s*(?:/?[A-Za-z]|!).*")) {
            for(String line:html.split("\\r?\\n")) if(!line.isBlank()) out.add(line.strip());
            return out;
        }
        var doc=Jsoup.parseBodyFragment(html);
        for(var br:doc.select("br")) br.after("\n");
        String selector="p,li,h1,h2,h3,h4,h5,h6,blockquote,pre,tr";
        var blocks=doc.body().select(selector);
        if(blocks.isEmpty()) {
            for(String line:doc.body().wholeText().split("\\r?\\n")) if(!line.isBlank()) out.add(line.strip());
            return out;
        }
        for(Element el:blocks) {
            if(el.select(selector).size()>1) continue; // 더 안쪽 블록이 있으면 안쪽 블록만 사용
            String text=el.tagName().equals("tr")
                    ?String.join(" | ",el.select("td,th").stream().map(Element::text).filter(v->!v.isBlank()).toList())
                    :el.wholeText().strip();
            if(!text.isBlank()) for(String line:text.split("\\r?\\n")) if(!line.isBlank()) out.add(line.strip());
        }
        for(var node:doc.body().textNodes()) if(!node.text().isBlank()) out.add(node.text().strip());
        return out;
    }

    // ---------- 유사도 ----------
    static String normalize(String s) { return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…“”‘’「」『』]+",""); }
    static Set<String> grams(String normalized) {
        var set=new HashSet<String>();
        for(int i=0;i+3<=normalized.length();i++) set.add(normalized.substring(i,i+3));
        return set;
    }
    /** 문단의 3-gram 중 AI 응답에도 나타나는 비율(포함률). 짧은 문단은 부분 문자열 일치로 판정합니다. */
    static double containment(String paragraph,String ai) {
        String p=normalize(paragraph),a=normalize(ai);
        if(p.isEmpty()||a.isEmpty()) return 0;
        if(p.length()<5) return a.contains(p)?1:0;
        var pg=grams(p);var ag=grams(a);
        if(pg.isEmpty()) return 0;
        long hit=pg.stream().filter(ag::contains).count();
        return (double)hit/pg.size();
    }

    // ---------- 분석 ----------
    public Analysis analyze(String participantId,JsonNode source,DisclosureInput disclosure,Instant now) {
        if(disclosure==null) disclosure=DisclosureInput.EMPTY;
        WorkModels.AiPolicy policy;
        try { policy=WorkModels.AiPolicy.valueOf(source.path("aiPolicy").asText("DISABLED")); }
        catch(IllegalArgumentException ex) { policy=WorkModels.AiPolicy.DISABLED; }

        // 1. 입력 수집
        var turns=new ArrayList<JsonNode>();source.path("conversations").forEach(turns::add);
        var events=new ArrayList<JsonNode>();source.path("learningEvents").forEach(events::add);
        events.sort(Comparator.comparingLong(e->e.path("id").asLong()));
        var completed=turns.stream().filter(t->"COMPLETED".equals(t.path("state").asText())&&!t.path("answerText").isNull()).toList();

        // 3. 질문 분류
        var classified=new ArrayList<ClassifiedQuestion>();
        for(var t:turns) {
            var type=classify(t.path("questionText").asText());
            classified.add(new ClassifiedQuestion(t.path("id").asLong(),t.path("requestId").asText(),t.path("questionId").asText(),type,type.label(),
                    type==QuestionType.OTHER?"RULE_UNMATCHED":"RULE"));
        }
        int totalQuestions=classified.size();
        var counts=new EnumMap<QuestionType,Integer>(QuestionType.class);
        for(var c:classified) counts.merge(c.type(),1,Integer::sum);

        // 4. 문단 출처 판정
        var declared=new HashMap<String,String>();
        var copyQuestions=new HashSet<String>();
        int paste=0,copy=0,warnings=0,longPaste=0;
        var warningNames=WorkModels.ClientEventType.warningNames();
        for(var e:events) {
            String type=e.path("type").asText(),qid=e.path("questionId").isNull()?null:e.path("questionId").asText(null);
            if(type.equals("ANSWER_SAVED")&&qid!=null) declared.put(qid,e.path("details").path("source").asText("DIRECT"));
            if(type.equals("PASTE")) { paste++; if(e.path("details").path("length").asInt(0)>=config.getLongPasteLength()) longPaste++; }
            if(type.equals("AI_ANSWER_COPY")) { copy++; if(qid!=null) copyQuestions.add(qid); }
            if(warningNames.contains(type)) warnings++;
        }
        var paragraphs=new ArrayList<Paragraph>();
        for(var entry:source.path("answers")) {
            var q=entry.path("question");String qid=q.path("id").asText();int pos=q.path("position").asInt();
            var texts=paragraphs(entry.path("answer").path("content").asText(""));
            for(int i=0;i<texts.size();i++) {
                String text=texts.get(i);double best=0;Long bestId=null;
                for(var t:completed) {
                    double sim=containment(text,t.path("answerText").asText());
                    if(sim>best||(sim==best&&bestId!=null&&qid.equals(t.path("questionId").asText()))) { best=sim;bestId=t.path("id").asLong(); }
                }
                double score=best;
                if(best>0&&copyQuestions.contains(qid)) score=Math.min(1,best+config.getCopyEventBonus());
                String src=score>=config.getAiUsedThreshold()?"AI_USED":score>=config.getAiReferencedThreshold()?"AI_REFERENCED":"DIRECT";
                paragraphs.add(new Paragraph("q"+pos+"-p"+(i+1),qid,pos,text,src,round(best,3),src.equals("DIRECT")?1.0:round(1-best,3),
                        src.equals("DIRECT")?null:bestId,true,declared.getOrDefault(qid,null)));
            }
        }
        long totalChars=paragraphs.stream().mapToLong(p->p.text().length()).sum();
        double directShare=0,refShare=0,usedShare=0;
        if(totalChars>0) for(var p:paragraphs) {
            double share=(double)p.text().length()/totalChars;
            switch(p.source()) {
                case "AI_USED" -> usedShare+=share;
                case "AI_REFERENCED" -> refShare+=share;
                default -> directShare+=share;
            }
        }
        var contribution=percentages(directShare,refShare,usedShare,totalChars==0);

        boolean aiUsed=!turns.isEmpty()||copy>0||paragraphs.stream().anyMatch(p->!p.source().equals("DIRECT"));
        var signals=new Signals(totalQuestions,completed.size(),paste,copy,warnings,!disclosure.tools().isBlank(),
                !disclosure.usedParts().isBlank(),!disclosure.reason().isBlank(),aiUsed);

        // 2. 허용 범위 검사
        var violations=new ArrayList<String>();var checks=new ArrayList<String>();
        int drafting=counts.getOrDefault(QuestionType.ANSWER_DRAFTING,0);
        long usedParagraphs=paragraphs.stream().filter(p->p.source().equals("AI_USED")).count();
        double maxUsed=config.getMaxAiUsedShare().getOrDefault(policy.level(),1.0);
        if(policy.level()==1&&!turns.isEmpty()) violations.add("AI 금지 평가인데 AI 대화가 "+turns.size()+"건 있습니다");
        if(policy.level()==1&&longPaste>0) checks.add(config.getLongPasteLength()+"자 이상 붙여넣기 "+longPaste+"건 (외부 출처 확인 필요)");
        if(policy.level()==1&&copy>0) checks.add("AI 답변 복사 기록 "+copy+"건");
        if(usedShare>maxUsed+1e-9) {
            String reason="허용 단계(Level "+policy.level()+") 대비 AI 문장을 그대로 사용한 비율이 높습니다 ("+usedParagraphs+"문단, "+Math.round(usedShare*100)+"%)";
            checks.add(reason+" (유사도 추정만으로 위반을 확정하지 않음)");
        }
        if(drafting>0&&policy.level()<=3) checks.add("답안 대리 작성 요청 "+drafting+"건");
        var compliance=new Compliance(!violations.isEmpty()?ComplianceResult.VIOLATION:!checks.isEmpty()?ComplianceResult.CHECK:ComplianceResult.OK,
                concat(violations,checks));

        // 5~7. 지표·수준·근거
        var indicators=new ArrayList<IndicatorResult>();
        // ① 목적 적절성
        if(totalQuestions==0) indicators.add(unavailable(Indicator.PURPOSE,"AI 질문 기록이 없어 목적 적절성을 판단할 근거가 없습니다 (교사 확인 필요).",List.of()));
        else {
            int appropriate=0;var ev=new ArrayList<Evidence>();
            for(var c:classified) {
                if(config.getAppropriateTypes().contains(c.type().name())) appropriate++;
                if(c.type()==QuestionType.ANSWER_DRAFTING) ev.add(new Evidence(c.conversationId(),null,null,"답안 대리 작성 요청"));
            }
            double s=clamp((double)appropriate/totalQuestions-config.getDraftingPenalty()*drafting/totalQuestions);
            indicators.add(result(Indicator.PURPOSE,s,"질문 "+totalQuestions+"개 중 "+appropriate+"개가 개념 이해·자료 탐색·아이디어 구체화 목적"
                    +(drafting>0?", 답안 대리 작성 요청 "+drafting+"건 감점":""),ev.isEmpty()?classified.stream().limit(5).map(c->new Evidence(c.conversationId(),null,null,c.label())).toList():ev));
        }
        // ② 자기 주도적 사고
        if(totalQuestions==0) indicators.add(unavailable(Indicator.SELF_DIRECTION,"관측된 AI 질문이 없어 자기 주도성을 판단할 수 없습니다. 교사 확인이 필요합니다.",List.of()));
        else {
            // (a) 문항별 첫 AI 질문 전에 학생이 내용을 저장했는지
            var firstAsk=new HashMap<String,Long>();var savedBefore=new HashSet<String>();
            for(var e:events) {
                String type=e.path("type").asText(),qid=e.path("questionId").asText("");
                if(type.equals("AI_REQUESTED")) firstAsk.putIfAbsent(qid,e.path("id").asLong());
                if(type.equals("ANSWER_SAVED")&&!firstAsk.containsKey(qid)&&!e.path("details").path("content").asText("").isBlank()
                        &&!paragraphs(e.path("details").path("content").asText("")).isEmpty()) savedBefore.add(qid);
            }
            var askedQuestions=new LinkedHashSet<String>();for(var c:classified) askedQuestions.add(c.questionId());
            double a=askedQuestions.isEmpty()?0:(double)askedQuestions.stream().filter(savedBefore::contains).count()/askedQuestions.size();
            // (b) 추가 질문을 스스로 구체화한 비율
            var seen=new HashSet<String>();int followUps=0,specific=0;
            for(var t:turns) {
                String qid=t.path("questionId").asText(),text=t.path("questionText").asText("");
                boolean isSpecific=text.strip().length()>=config.getSpecificQuestionMinLength()||FOLLOW_UP_REFERENCE.matcher(text).find();
                if(seen.add(qid)) continue;
                followUps++; if(isSpecific&&classify(text)!=QuestionType.ANSWER_DRAFTING) specific++;
            }
            if(followUps==0) for(var t:turns) { followUps++; String text=t.path("questionText").asText(""); if(text.strip().length()>=config.getSpecificQuestionMinLength()&&classify(text)!=QuestionType.ANSWER_DRAFTING) specific++; }
            double b=followUps==0?0:(double)specific/followUps;
            // (c) 선택 이유 작성 여부
            double c=disclosure.reason().isBlank()?0:1;
            double s=(a+b+c)/3;
            indicators.add(result(Indicator.SELF_DIRECTION,s,String.format(Locale.ROOT,
                    "첫 AI 질문 전 자기 답안 작성 %d/%d문항, 구체적인 추가 질문 %d/%d건, AI 선택 이유 %s",
                    askedQuestions.stream().filter(savedBefore::contains).count(),askedQuestions.size(),specific,followUps,c>0?"작성":"미작성"),List.of()));
        }
        // ③ AI 답변의 비판적 검토
        if(completed.isEmpty()) indicators.add(unavailable(Indicator.CRITICAL_REVIEW,"완료된 AI 응답이 없어 검토 대상이 없습니다 (교사 확인 필요).",List.of()));
        else {
            int acted=0;var ev=new ArrayList<Evidence>();
            for(int i=0;i<turns.size();i++) {
                var t=turns.get(i);if(!"COMPLETED".equals(t.path("state").asText())||t.path("answerText").isNull()) continue;
                long id=t.path("id").asLong();String qid=t.path("questionId").asText();boolean action=false;String note=null;
                for(int j=i+1;j<turns.size();j++) if(turns.get(j).path("questionId").asText().equals(qid)) {
                    if(classify(turns.get(j).path("questionText").asText())==QuestionType.VERIFICATION) { action=true;note="후속 검증·반박 질문"; }
                    break;
                }
                if(!action) for(var p:paragraphs) if(p.source().equals("AI_REFERENCED")&&Objects.equals(p.conversationId(),id)) { action=true;note="AI 내용을 수정해 반영 ("+p.id()+")";break; }
                if(action) { acted++; ev.add(new Evidence(id,null,null,note)); }
            }
            double s=(double)acted/completed.size();
            indicators.add(result(Indicator.CRITICAL_REVIEW,s,"AI 응답 "+completed.size()+"건 중 "+acted+"건에서 확인·반박·수정 행동이 있었습니다",ev));
        }
        // 유사도는 저자 기여도의 검증된 측정값이 아니므로 이 영역은 교사가 채점합니다.
        indicators.add(unavailable(Indicator.CONTRIBUTION,"텍스트 유사도로 실제 학생 기여도를 계산할 수 없습니다. 교사가 답안과 과정 근거를 확인해 채점해야 합니다.",List.of()));
        // ⑤ 투명성
        if(!aiUsed) indicators.add(unavailable(Indicator.TRANSPARENCY,"AI 사용 기록이 없어 기록할 대상이 없습니다 (교사 확인 필요).",List.of()));
        else {
            int recorded=(signals.disclosedTools()?1:0)+(totalQuestions>0?1:0)+(signals.disclosedUsedParts()?1:0);
            indicators.add(result(Indicator.TRANSPARENCY,recorded/3.0,"필수 기록 3개 중 "+recorded+"개 (사용 AI 도구 "+(signals.disclosedTools()?"O":"X")
                    +", 질문 기록 "+(totalQuestions>0?"O":"X")+", 답안 활용 부분 "+(signals.disclosedUsedParts()?"O":"X")+")",List.of()));
        }

        var usage=new ArrayList<Usage>();
        for(var entry:counts.entrySet()) {
            double share=(double)entry.getValue()/totalQuestions;
            usage.add(new Usage(entry.getKey(),entry.getKey().label(),entry.getValue(),round(share,3),
                    share>=config.getUsageHighShare()?"HIGH":share>=config.getUsageMediumShare()?"MEDIUM":"LOW"));
        }
        usage.sort(Comparator.comparingInt(Usage::count).reversed().thenComparing(u->u.type().ordinal()));

        String summary=summary(totalQuestions,counts,contribution,compliance,paragraphs.isEmpty());
        return new Analysis(participantId,"DONE",MODEL,policy.name(),policy.level(),compliance,contribution,usage,classified,paragraphs,
                indicators,signals,summary,now,List.of(
                        "규칙 기반 추천 점수이며 근거가 없으면 null입니다. 최종 점수는 교사가 확정합니다.",
                        "텍스트 유사도 비율은 실제 학생 기여도가 아니며 이 영역의 추천 점수는 제공하지 않습니다.",
                        "질문 분류는 키워드 규칙 기반이며 LLM 분류는 사용하지 않습니다. 기타(RULE_UNMATCHED)는 교사 확인이 필요합니다.",
                        "문단 출처는 AI 응답과의 문장 유사도·복사 기록으로 추정한 값입니다(estimated=true). 외부 AI·저장되지 않은 입력은 관측할 수 없습니다.",
                        "허용 범위 위반 표시만으로 점수를 자동 감점하지 않습니다."));
    }

    private String summary(int questions,Map<QuestionType,Integer> counts,Contribution c,Compliance compliance,boolean empty) {
        var sb=new StringBuilder();
        if(questions==0) sb.append("AI 질문 기록이 없습니다. ");
        else {
            int good=counts.getOrDefault(QuestionType.CONCEPT_UNDERSTANDING,0)+counts.getOrDefault(QuestionType.RESEARCH,0)+counts.getOrDefault(QuestionType.IDEA_EXPLORATION,0);
            sb.append("AI 질문 ").append(questions).append("건 중 ").append(good).append("건이 개념 이해·자료 탐색·아이디어 구체화 목적이었습니다. ");
        }
        if(empty) sb.append("최종 답안이 비어 있습니다. ");
        else sb.append("최종 답안의 낮은 유사도 비중은 ").append(c.direct()).append("%, 부분 유사도 ").append(c.aiReferenced())
                .append("%, 높은 유사도 ").append(c.aiUsed()).append("%입니다. 실제 저자 기여도 비율은 아닙니다. ");
        sb.append("허용 범위 점검: ").append(switch(compliance.result()) { case OK -> "문제 없음"; case CHECK -> "확인 필요"; case VIOLATION -> "위반 가능성"; });
        if(!compliance.reasons().isEmpty()) sb.append(" (").append(String.join(", ",compliance.reasons())).append(")");
        return sb.append(".").toString();
    }
    private IndicatorResult unavailable(Indicator indicator,String reason,List<Evidence> evidence) {
        return new IndicatorResult(indicator,null,null,reason,evidence);
    }
    private IndicatorResult result(Indicator indicator,double s,String reason,List<Evidence> evidence) {
        s=clamp(s);
        return new IndicatorResult(indicator,round(s,3),(int)Math.round(4*s),reason,evidence);
    }
    private static Contribution percentages(double d,double r,double u,boolean empty) {
        if(empty) return new Contribution(0,0,0,"TEXT_SIMILARITY_ESTIMATE");
        double[] raw={d*100,r*100,u*100};int[] floor=new int[3];int sum=0;
        for(int i=0;i<3;i++) { floor[i]=(int)Math.floor(raw[i]); sum+=floor[i]; }
        Integer[] order={0,1,2};
        Arrays.sort(order,(x,y)->Double.compare(raw[y]-floor[y],raw[x]-floor[x]));
        for(int i=0;i<100-sum&&i<3;i++) floor[order[i]]++;
        return new Contribution(floor[0],floor[1],floor[2],"TEXT_SIMILARITY_ESTIMATE");
    }
    private static List<String> concat(List<String> a,List<String> b) { var out=new ArrayList<>(a);out.addAll(b);return List.copyOf(out); }
    static double clamp(double v) { return Double.isNaN(v)?0:Math.max(0,Math.min(1,v)); }
    static double round(double v,int digits) { double m=Math.pow(10,digits);return Math.round(v*m)/m; }
}
