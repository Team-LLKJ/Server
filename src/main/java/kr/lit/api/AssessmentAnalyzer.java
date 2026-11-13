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
 * 허용 범위 검사 → 질문 분류 → 문장 출처 판정(V2: 직접 작성/AI 답변 사용) → 지표(0~1) → 수준(round(4×s))을 계산합니다.
 * DB에 접근하지 않는 순수 계산이므로 같은 입력은 항상 같은 결과를 냅니다.
 */
@Component
public class AssessmentAnalyzer {
    public static final String MODEL="LIT_RULE_BASED_EXPLAINED_V2_1";
    public static final String CONTRIBUTION_BASIS="TEXT_SIMILARITY_ESTIMATE_V2";
    public static final String SOURCE_DIRECT="DIRECT",SOURCE_AI="AI_REFERENCED",SENTENCE_AI="AI",REASON_SPAN="SPAN_MATCH",REASON_GRAM="GRAM_OVERLAP",REASON_SEMANTIC="SEMANTIC",REASON_TEACHER="TEACHER",LABEL_AI="AI",LABEL_DIRECT="DIRECT";
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
    /**
     * 질문 하나의 분류. 스냅샷에 저장된 질문 용도(purposeClassification: 교사 확정 > LLM)가 있으면 그것을 쓰고,
     * 없거나 UNDECIDED이면 키워드 규칙으로 대체합니다. PurposeUsageAnalyzer와 같은 분류 결과를 씁니다.
     */
    public static QuestionType classify(JsonNode turn) {
        String text=turn.path("questionText").asText("");
        String purpose=turn.path("purposeClassification").path("purpose").asText("");
        return switch(purpose) {
            case "UNDERSTAND" -> classify(text)==QuestionType.RESEARCH?QuestionType.RESEARCH:QuestionType.CONCEPT_UNDERSTANDING;
            case "PLAN" -> QuestionType.IDEA_EXPLORATION;
            case "REVIEW" -> QuestionType.VERIFICATION;
            case "EDIT" -> QuestionType.EDIT;
            case "DRAFT" -> QuestionType.ANSWER_DRAFTING;
            case "OTHER" -> QuestionType.OTHER;
            default -> classify(text);
        };
    }
    /** 분류 출처: TEACHER(교사 확정), LLM, 아니면 키워드 규칙(RULE/RULE_UNMATCHED). */
    static String classificationMethod(JsonNode turn,QuestionType type) {
        var c=turn.path("purposeClassification");String purpose=c.path("purpose").asText("");
        if(!purpose.isEmpty()&&!purpose.equals("UNDECIDED")) return c.path("teacherPurpose").isTextual()?"TEACHER":"LLM";
        return type==QuestionType.OTHER?"RULE_UNMATCHED":"RULE";
    }
    public static QuestionType classify(String text) {
        String t=text==null?"":text.toLowerCase(Locale.ROOT);
        for(var rule:RULES) if(rule.getValue().matcher(t).find()) return rule.getKey();
        return QuestionType.OTHER;
    }
    static Pattern getClassificationPattern(QuestionType type){return RULES.stream().filter(rule->rule.getKey()==type).map(rule->Pattern.compile(rule.getValue().pattern(),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE)).findFirst().orElse(null);}
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

    private static List<ReasonEvidence> getReasonEvidence(List<Evidence> evidence,String type) {
        return evidence.stream().filter(e->type.equals(e.type())&&e.quote()!=null)
                .map(e->new ReasonEvidence(e.conversationId(),e.quote())).distinct().toList();
    }

    // ---------- 분석 ----------
    public Analysis analyze(String participantId,JsonNode source,DisclosureInput disclosure,Instant now) {
        return analyze(participantId,source,disclosure,now,SemanticInput.DISABLED);
    }
    /** 문장 판정 키. 2단계 LLM 판정 캐시·입력에서 같은 문장을 찾을 때 씁니다. */
    static String sentenceKey(String questionId,String sentence) { return questionId+"\u0001"+sentence; }
    /** 비교에서 뺄 문구: 평가 안내 + 문항 제목 + 문항 내용. */
    static String excludedText(JsonNode source,JsonNode question) {
        return plainText(source.path("evaluationContent").asText(""))+"\n"+question.path("title").asText("")+"\n"+plainText(question.path("content").asText(""));
    }
    /**
     * semantic: 백그라운드에서 저장해 둔 2단계 LLM 판정. 1단계에서 DIRECT로 남은 문장에만 적용합니다.
     * 판정은 입력으로만 받으므로 이 메서드는 계속 같은 입력에 같은 결과를 내는 순수 계산입니다.
     */
    public Analysis analyze(String participantId,JsonNode source,DisclosureInput disclosure,Instant now,SemanticInput semantic) {
        return analyze(participantId,source,disclosure,now,semantic,Map.of());
    }
    /** teacherLabels: 교사가 고친 문장 판정(sentenceKey → AI|DIRECT). 알고리즘 판정보다 우선하며 비율에 바로 반영됩니다. */
    public Analysis analyze(String participantId,JsonNode source,DisclosureInput disclosure,Instant now,SemanticInput semantic,Map<String,String> teacherLabels) {
        if(semantic==null) semantic=SemanticInput.DISABLED;
        if(teacherLabels==null) teacherLabels=Map.of();
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
            var type=classify(t);
            classified.add(new ClassifiedQuestion(t.path("id").asLong(),t.path("requestId").asText(),t.path("questionId").asText(),type,type.label(),
                    classificationMethod(t,type)));
        }
        int totalQuestions=classified.size();
        var counts=new EnumMap<QuestionType,Integer>(QuestionType.class);
        for(var c:classified) counts.merge(c.type(),1,Integer::sum);

        // 4. 문장 출처 판정 (V2) — 문단을 문장으로 나눠 DIRECT / AI_REFERENCED 두 가지로만 판정합니다.
        //   ① 문항·안내 문구를 뺀 정규화 구간이 minSpanLength 이상 AI 답변과 그대로 이어지면 AI (SPAN_MATCH)
        //   ② 문항·안내 문구를 뺀 3-gram 포함률 >= aiReferencedThreshold 이면 AI (GRAM_OVERLAP)
        //   ③ 그 외 DIRECT. AI 답변 복사 기록 가산점은 쓰지 않습니다(복사했다면 ①로 잡힘).
        var declared=new HashMap<String,String>();
        int paste=0,copy=0,warnings=0,longPaste=0;
        var warningNames=WorkModels.ClientEventType.warningNames();
        for(var e:events) {
            String type=e.path("type").asText(),qid=e.path("questionId").isNull()?null:e.path("questionId").asText(null);
            if(type.equals("ANSWER_SAVED")&&qid!=null) declared.put(qid,e.path("details").path("source").asText("DIRECT"));
            if(type.equals("PASTE")) { paste++; if(e.path("details").path("length").asInt(0)>=config.getLongPasteLength()) longPaste++; }
            if(type.equals("AI_ANSWER_COPY")) copy++;
            if(warningNames.contains(type)) warnings++;
        }
        int minSpan=config.getMinSpanLength();double gramThreshold=config.getAiReferencedThreshold();
        var references=new ArrayList<Map.Entry<JsonNode,SentenceSimilarity.Reference>>();
        for(var t:completed) references.add(Map.entry(t,new SentenceSimilarity.Reference(t.path("answerText").asText(""),minSpan)));
        // 출처는 대화에서 처음 나온 사람: 학생 질문에 먼저 나온 표현을 AI가 되풀이했으면 학생 것으로 보고 비교에서 뺍니다(앞선 AI 답변에서 옮긴 표현은 빼지 않음).
        var origins=studentOrigins(turns,minSpan);
        // 긴 붙여넣기 구간(문항별, 같은 답안 버전). LIT AI 답변과 겹치지 않는 직접 작성 문장이라도 다른 AI에서 가져왔을 수 있어 교사 확인 대상으로 둡니다.
        var pastes=new HashMap<String,List<int[]>>();
        for(var p:AssessmentTrace.getPasteEvents(source))
            if(p.matchesFinalAnswer()&&p.length()!=null&&p.length()>=config.getLongPasteLength()) pastes.computeIfAbsent(p.questionId()+"@"+p.answerVersion(),k->new ArrayList<>()).add(new int[]{p.start(),p.end()});
        var paragraphs=new ArrayList<Paragraph>();var sentences=new ArrayList<Sentence>();var segments=new ArrayList<Segment>();
        long totalChars=0,aiChars=0;
        for(var entry:source.path("answers")) {
            var q=entry.path("question");String qid=q.path("id").asText();int pos=q.path("position").asInt();int version=entry.path("answer").path("version").asInt();
            var exclusion=new SentenceSimilarity.Exclusion(excludedText(source,q));
            var exclusions=new HashMap<Long,SentenceSimilarity.Exclusion>();
            for(var ref:references) { long id=ref.getKey().path("id").asLong();exclusions.put(id,exclusion.withStudentOrigin(origins.get(id),minSpan)); }
            var texts=paragraphs(entry.path("answer").path("content").asText(""));
            int offset=0;
            for(int i=0;i<texts.size();i++) {
                String text=texts.get(i);String paragraphId="q"+pos+"-p"+(i+1);
                var ranges=SentenceSimilarity.sentences(text);
                double paragraphBest=0;Long paragraphConversation=null;boolean paragraphAi=false;int paragraphRank=-1;
                for(int k=0;k<ranges.size();k++) {
                    var range=ranges.get(k);String sentence=text.substring(range.start(),range.end());
                    double best=0;int longest=0;Long conversation=null;double rankOverlap=-1;int rankSpan=-1;boolean rankSame=false;String matchedAnswer=null;
                    SentenceSimilarity.Exclusion matchedExclusion=exclusion;
                    for(var ref:references) {
                        var refExclusion=exclusions.get(ref.getKey().path("id").asLong());
                        var match=SentenceSimilarity.compare(sentence,ref.getValue(),refExclusion);
                        best=Math.max(best,match.overlap());longest=Math.max(longest,match.longestSpan());
                        // 근거 응답: 긴 일치 구간 → 포함률 → 같은 문항 순으로 고릅니다.
                        int spanKey=match.longestSpan()>=minSpan?match.longestSpan():0;boolean same=qid.equals(ref.getKey().path("questionId").asText());
                        if(match.overlap()==0&&match.longestSpan()==0) continue;
                        if(spanKey>rankSpan||spanKey==rankSpan&&(match.overlap()>rankOverlap||match.overlap()==rankOverlap&&same&&!rankSame)) {
                            rankSpan=spanKey;rankOverlap=match.overlap();rankSame=same;conversation=ref.getKey().path("id").asLong();matchedAnswer=ref.getKey().path("answerText").asText("");matchedExclusion=refExclusion;
                        }
                    }
                    String reason=longest>=minSpan?REASON_SPAN:best>=gramThreshold?REASON_GRAM:null;
                    // 2단계: 글자 비교로 DIRECT가 된 문장만 저장된 LLM 판정을 적용합니다.
                    var verdict=semantic.verdicts().get(sentenceKey(qid,sentence));
                    if(reason!=null) verdict=null;
                    if(verdict!=null&&verdict.ai()) { reason=REASON_SEMANTIC;conversation=verdict.conversationId(); }
                    String teacherLabel=teacherLabels.get(sentenceKey(qid,sentence));
                    boolean needsReview=verdict!=null&&verdict.needsReview();
                    int sStart=offset+range.start(),sEnd=offset+range.end();
                    boolean pasted=pastes.getOrDefault(qid+"@"+version,List.of()).stream().anyMatch(r->r[0]<sEnd&&r[1]>sStart);
                    if(pasted&&reason==null) needsReview=true;
                    if(teacherLabel!=null) {
                        needsReview=false;
                        if(LABEL_DIRECT.equals(teacherLabel)) reason=null;
                        else if(reason==null) { reason=REASON_TEACHER;if(conversation==null&&verdict!=null) conversation=verdict.conversationId(); }
                    }
                    // 문장 출처는 DIRECT/AI 두 가지(문단·구간 유형은 기존 AI_REFERENCED 이름 유지).
                    String src=reason==null?SOURCE_DIRECT:SENTENCE_AI;
                    // 문장 사이 공백까지 포함해 답안 텍스트를 빈틈없이 덮는 구간(segments)으로 비중을 계산합니다.
                    int segStart=k==0?0:range.start(),segEnd=k+1<ranges.size()?ranges.get(k+1).start():text.length();
                    int length=segEnd-segStart;totalChars+=length;if(reason!=null) aiChars+=length;
                    double similarity=round(best,3);
                    sentences.add(new Sentence(paragraphId+"-s"+(k+1),qid,paragraphId,sentence,src,reason,longest,similarity,
                            reason==null?null:conversation,offset+range.start(),offset+range.end(),version,true,
                            needsReview,verdict,teacherLabel,
                            (REASON_SPAN.equals(reason)||REASON_GRAM.equals(reason))?SentenceSimilarity.findAnswerMatch(sentence,matchedAnswer,matchedExclusion,minSpan):null,pasted));
                    segments.add(new Segment(qid,offset+segStart,offset+segEnd,reason==null?"STUDENT":SOURCE_AI,similarity,similarity,true,version));
                    paragraphBest=Math.max(paragraphBest,best);
                    if(reason!=null) {
                        paragraphAi=true;int rank=reason.equals(REASON_SPAN)?3:reason.equals(REASON_GRAM)?2:1;
                        if(rank>paragraphRank) { paragraphRank=rank;paragraphConversation=conversation; }
                    }
                }
                String src=paragraphAi?SOURCE_AI:SOURCE_DIRECT;
                paragraphs.add(new Paragraph(paragraphId,qid,pos,text,src,paragraphBest,paragraphAi?round(1-paragraphBest,3):1.0,
                        paragraphConversation,true,declared.getOrDefault(qid,null)));
                offset+=text.length()+1;
            }
        }
        double aiShare=totalChars==0?0:(double)aiChars/totalChars;
        var contribution=percentages(aiShare,totalChars==0);

        boolean aiUsed=!turns.isEmpty()||copy>0||paragraphs.stream().anyMatch(p->!p.source().equals("DIRECT"));
        var signals=new Signals(totalQuestions,completed.size(),paste,copy,warnings,!disclosure.tools().isBlank(),
                !disclosure.usedParts().isBlank(),!disclosure.reason().isBlank(),aiUsed);

        // 2. 허용 범위 검사
        var violations=new ArrayList<String>();var checks=new ArrayList<String>();
        int drafting=counts.getOrDefault(QuestionType.ANSWER_DRAFTING,0);
        long aiSentences=sentences.stream().filter(x->x.reason()!=null).count();
        double maxUsed=config.getMaxAiUsedShare().getOrDefault(policy.level(),1.0);
        if(policy.level()==1&&!turns.isEmpty()) violations.add("AI 금지 평가인데 AI 대화가 "+turns.size()+"건 있습니다");
        if(policy.level()==1&&longPaste>0) checks.add(config.getLongPasteLength()+"자 이상 붙여넣기 "+longPaste+"건 (외부 출처 확인 필요)");
        if(policy.level()==1&&copy>0) checks.add("AI 답변 복사 기록 "+copy+"건");
        if(aiShare>maxUsed+1e-9) {
            String reason="허용 단계(Level "+policy.level()+") 대비 AI 답변을 사용한 것으로 추정되는 비율이 높습니다 ("+aiSentences+"문장, "+contribution.aiReferenced()+"%)";
            checks.add(reason+" (유사도 추정만으로 위반을 확정하지 않음)");
        }
        if(drafting>0&&policy.level()<=3) checks.add("답안 대리 작성 요청 "+drafting+"건");
        var compliance=new Compliance(!violations.isEmpty()?ComplianceResult.VIOLATION:!checks.isEmpty()?ComplianceResult.CHECK:ComplianceResult.OK,
                concat(violations,checks));

        // 5~7. 지표·수준·근거
        var indicators=new ArrayList<IndicatorResult>();
        var explanations=new EnumMap<Indicator,List<Reason>>(Indicator.class);
        // ① 목적 적절성
        if(totalQuestions==0) indicators.add(unavailable(Indicator.PURPOSE,"AI 질문 기록이 없어 목적 적절성을 판단할 근거가 없습니다 (교사 확인 필요).",List.of()));
        else {
            int appropriate=0;var ev=new ArrayList<Evidence>();
            for(var c:classified) {
                if(config.getAppropriateTypes().contains(c.type().name())) appropriate++;
                if(c.type()==QuestionType.ANSWER_DRAFTING) ev.add(new Evidence(c.conversationId(),null,null,"답안 대리 작성 요청"));
            }
            var purposeReasons=new ArrayList<Reason>();
            var purposeEvidence=AssessmentTrace.getEvidence(Indicator.PURPOSE,turns,sentences,config.getAppropriateTypes(),config.getSpecificQuestionMinLength());
            if(appropriate>0)purposeReasons.add(new Reason("CREDIT","AI 질문 "+totalQuestions+"건 중 "+appropriate+"건이 학습·탐색 목적 분류에 포함됐습니다.",null,getReasonEvidence(purposeEvidence,"CREDIT")));
            if(totalQuestions>appropriate)purposeReasons.add(new Reason("DEDUCTION","학습·탐색 목적 분류 밖의 질문은 "+(totalQuestions-appropriate)+"건입니다.",null,getReasonEvidence(purposeEvidence,"DEDUCTION")));
            if(drafting>0)purposeReasons.add(new Reason("DEDUCTION","답안 작성 요청 "+drafting+"건이 목적 적절성 산식에 반영됐습니다.",null,getReasonEvidence(purposeEvidence.stream().filter(e->e.reason().startsWith("답안 작성 요청")).toList(),"DEDUCTION")));
            explanations.put(Indicator.PURPOSE,purposeReasons);
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
                followUps++; if(isSpecific&&classify(t)!=QuestionType.ANSWER_DRAFTING) specific++;
            }
            if(followUps==0) for(var t:turns) { followUps++; String text=t.path("questionText").asText(""); if(text.strip().length()>=config.getSpecificQuestionMinLength()&&classify(t)!=QuestionType.ANSWER_DRAFTING) specific++; }
            double b=followUps==0?0:(double)specific/followUps;
            // (c) 선택 이유 작성 여부
            double c=disclosure.reason().isBlank()?0:1;
            var selfReasons=new ArrayList<Reason>();
            var selfEvidence=AssessmentTrace.getEvidence(Indicator.SELF_DIRECTION,turns,sentences,config.getAppropriateTypes(),config.getSpecificQuestionMinLength());
            long prior=askedQuestions.stream().filter(savedBefore::contains).count();
            if(prior>0)selfReasons.add(new Reason("CREDIT","AI 질문 전 답안 저장 "+prior+"/"+askedQuestions.size()+"문항",null));
            if(prior<askedQuestions.size())selfReasons.add(new Reason("DEDUCTION","AI 질문 전 답안 저장 미관측 "+(askedQuestions.size()-prior)+"/"+askedQuestions.size()+"문항",null));
            if(specific>0)selfReasons.add(new Reason("CREDIT","구체화 규칙 충족 "+specific+"/"+followUps+"건",null,getReasonEvidence(selfEvidence,"CREDIT")));
            if(specific<followUps)selfReasons.add(new Reason("DEDUCTION","구체화 규칙 미충족 "+(followUps-specific)+"/"+followUps+"건",null,getReasonEvidence(selfEvidence,"DEDUCTION")));
            selfReasons.add(new Reason(c>0?"CREDIT":"DEDUCTION","AI 선택 이유 "+(c>0?"작성":"미작성"),null));
            explanations.put(Indicator.SELF_DIRECTION,selfReasons);
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
                    if(classify(turns.get(j))==QuestionType.VERIFICATION) { action=true;note="후속 검증·반박 질문"; }
                    break;
                }
                // 긴 일치 구간 없이 내용만 겹치는 문장(GRAM_OVERLAP)은 AI 답변을 고쳐 쓴 신호로 봅니다.
                if(!action) for(var x:sentences) if(REASON_GRAM.equals(x.reason())&&Objects.equals(x.conversationId(),id)) { action=true;note="AI 내용을 수정해 반영 ("+x.id()+")";break; }
                if(action) { acted++; ev.add(new Evidence(id,null,null,note)); }
            }
            var reviewReasons=new ArrayList<Reason>();
            var reviewEvidence=AssessmentTrace.getEvidence(Indicator.CRITICAL_REVIEW,turns,sentences,config.getAppropriateTypes(),config.getSpecificQuestionMinLength());
            if(acted>0)reviewReasons.add(new Reason("CREDIT","완료 응답 "+completed.size()+"건 중 "+acted+"건에서 후속 검증·부분 유사도 수정 신호가 관측됐습니다.",null,getReasonEvidence(reviewEvidence,"CREDIT")));
            if(acted<completed.size())reviewReasons.add(new Reason("DEDUCTION","완료 응답 "+(completed.size()-acted)+"건에서는 검토 신호를 확인하지 못했습니다.",null,getReasonEvidence(reviewEvidence,"DEDUCTION")));
            explanations.put(Indicator.CRITICAL_REVIEW,reviewReasons);
            double s=(double)acted/completed.size();
            indicators.add(result(Indicator.CRITICAL_REVIEW,s,"AI 응답 "+completed.size()+"건 중 "+acted+"건에서 확인·반박·수정 행동이 있었습니다",ev));
        }
        // 유사도는 저자 기여도의 검증된 측정값이 아니므로 이 영역은 교사가 채점합니다.
        indicators.add(unavailable(Indicator.CONTRIBUTION,"텍스트 유사도로 실제 학생 기여도를 계산할 수 없습니다. 교사가 답안과 과정 근거를 확인해 채점해야 합니다.",List.of()));
        // ⑤ 투명성
        if(!aiUsed) indicators.add(unavailable(Indicator.TRANSPARENCY,"AI 사용 기록이 없어 기록할 대상이 없습니다 (교사 확인 필요).",List.of()));
        else if ("SYSTEM_GENERATED".equals(source.path("aiDisclosure").path("source").asText())
                && !source.path("studentAiDisclosure").isObject()) {
            if (totalQuestions==0) {
                indicators.add(unavailable(Indicator.TRANSPARENCY,"확인 가능한 AI 대화 기록이 없습니다.",List.of()));
            } else {
                var evidence=AssessmentTrace.getEvidence(Indicator.TRANSPARENCY,turns,sentences,config.getAppropriateTypes(),config.getSpecificQuestionMinLength());
                String reason="서버에 저장된 AI 질문 기록을 확인했습니다. 자동 생성된 활용 설명에는 미작성 감점을 적용하지 않습니다.";
                explanations.put(Indicator.TRANSPARENCY,List.of(new Reason("CREDIT",reason,null,getReasonEvidence(evidence,"CREDIT"))));
                indicators.add(result(Indicator.TRANSPARENCY,1.0,reason,evidence));
            }
        } else {
            int recorded=(signals.disclosedTools()?1:0)+(totalQuestions>0?1:0)+(signals.disclosedUsedParts()?1:0);
            var transparencyReasons=new ArrayList<Reason>();
            transparencyReasons.add(new Reason(signals.disclosedTools()?"CREDIT":"DEDUCTION","AI 도구명 "+(signals.disclosedTools()?"작성":"미작성"),null));
            transparencyReasons.add(new Reason(totalQuestions>0?"CREDIT":"DEDUCTION","서버 질문 기록 "+totalQuestions+"건",null,getReasonEvidence(AssessmentTrace.getEvidence(Indicator.TRANSPARENCY,turns,sentences,config.getAppropriateTypes(),config.getSpecificQuestionMinLength()),"CREDIT")));
            transparencyReasons.add(new Reason(signals.disclosedUsedParts()?"CREDIT":"DEDUCTION","답안 활용 부분 "+(signals.disclosedUsedParts()?"작성":"미작성"),null));
            explanations.put(Indicator.TRANSPARENCY,transparencyReasons);
            indicators.add(result(Indicator.TRANSPARENCY,recorded/3.0,"필수 기록 3개 중 "+recorded+"개 (사용 AI 도구 "+(signals.disclosedTools()?"O":"X")
                    +", 질문 기록 "+(totalQuestions>0?"O":"X")+", 답안 활용 부분 "+(signals.disclosedUsedParts()?"O":"X")+")",List.of()));
        }

        var usage=new ArrayList<Usage>();
        var usageCounts=new EnumMap<UsageType,Integer>(UsageType.class);
        counts.forEach((type,count)->usageCounts.merge(UsageType.fromStoredValue(type.name()),count,Integer::sum));
        for(var entry:usageCounts.entrySet()) {
            double share=(double)entry.getValue()/totalQuestions;
            usage.add(new Usage(entry.getKey(),PurposeUsageAnalyzer.Purpose.valueOf(entry.getKey().name()).label,entry.getValue(),round(share,3),
                    share>=config.getUsageHighShare()?"HIGH":share>=config.getUsageMediumShare()?"MEDIUM":"LOW"));
        }
        usage.sort(Comparator.comparingInt(Usage::count).reversed().thenComparing(u->u.type().ordinal()));

        var structured=indicators.stream().map(i->new IndicatorResult(i.indicator(),i.value(),i.level(),i.reason(),i.level()==null?i.evidence():AssessmentTrace.getEvidence(i.indicator(),turns,sentences,config.getAppropriateTypes(),config.getSpecificQuestionMinLength()),List.copyOf(explanations.getOrDefault(i.indicator(),List.of())))).toList();
        var trace=AssessmentTrace.buildTextTrace(source,paragraphs);
        contribution=new Contribution(contribution.direct(),contribution.aiReferenced(),contribution.aiUsed(),contribution.basis(),List.copyOf(segments));
        String summary=summary(totalQuestions,counts,contribution,compliance,paragraphs.isEmpty())+" 판단 요인: "+structured.stream().flatMap(i->i.reasons().stream()).map(r->(r.type().equals("CREDIT")?"가점 요인: ":"감점 요인: ")+r.text()).collect(java.util.stream.Collectors.joining(" · "));
        return new Analysis(participantId,"DONE",MODEL,policy.name(),policy.level(),compliance,contribution,usage,classified,paragraphs,
                structured,signals,summary,now,List.of(
                        "규칙 기반 추천 점수이며 근거가 없으면 null입니다. 최종 점수는 교사가 확정합니다.",
                        "텍스트 유사도 비율은 실제 학생 기여도가 아니며 이 영역의 추천 점수는 제공하지 않습니다.",
                        "질문 분류는 키워드 규칙 기반이며 LLM 분류는 사용하지 않습니다. 기타(RULE_UNMATCHED)는 교사 확인이 필요합니다.",
                        "답안 출처는 문장마다 내장 AI 응답과의 긴 일치 구간·3-gram 포함률(문항·안내 문구 제외)로 추정한 값입니다(estimated=true). "+semanticLimitation(semantic.status())+" 외부 AI·저장되지 않은 입력은 관측할 수 없습니다.",
                        "허용 범위 위반 표시만으로 점수를 자동 감점하지 않습니다.","구조화 사유는 기존 규칙 산식의 관측 요인이며 개별 점수 증감량을 분리할 수 없어 scoreDelta를 생략합니다."),trace.texts(),AssessmentTrace.getPasteEvents(source),getDefinition(),List.copyOf(sentences),semantic.status());
    }

    /** 대화 id → 그 대화의 답변 시점까지 학생이 먼저 말한 표현(대화 id 순 = 시간 순). */
    static Map<Long,SentenceSimilarity.StudentOrigin> studentOrigins(List<JsonNode> turns,int minSpan) {
        var ordered=turns.stream().sorted(Comparator.comparingLong(t->t.path("id").asLong())).toList();
        var questions=ordered.stream().map(t->t.path("questionText").asText("")).toList();
        var answers=ordered.stream().map(t->"COMPLETED".equals(t.path("state").asText())&&t.hasNonNull("answerText")?t.path("answerText").asText(""):null).toList();
        var list=SentenceSimilarity.StudentOrigin.chronological(questions,answers,minSpan);
        var out=new HashMap<Long,SentenceSimilarity.StudentOrigin>();
        for(int i=0;i<ordered.size();i++) out.put(ordered.get(i).path("id").asLong(),list.get(i));
        return out;
    }
    public Map<String,Object> getDefinition(){return Map.ofEntries(Map.entry("model",MODEL),Map.entry("basis",CONTRIBUTION_BASIS),
            Map.entry("sources",List.of(SOURCE_DIRECT,SENTENCE_AI)),Map.entry("unit","SENTENCE"),Map.entry("reasons",List.of(REASON_SPAN,REASON_GRAM,REASON_SEMANTIC,REASON_TEACHER)),
            Map.entry("minSpanLength",config.getMinSpanLength()),Map.entry("aiReferencedThreshold",config.getAiReferencedThreshold()),
            Map.entry("excludedText","평가 안내 + 문항 제목·내용 + 학생이 먼저 말한 표현(그 AI 답변 시점까지의 학생 질문, 앞선 AI 답변에서 옮긴 표현 제외)"),Map.entry("indexUnit","UTF16_END_EXCLUSIVE"),
            Map.entry("textNormalization","answerTexts.text (문단 사이 LF, HTML 제거·표 셀은 | 구분)"),Map.entry("usageMediumPercentage",20),
            Map.entry("usageHighPercentage",50),Map.entry("studentContribution","실제 작성 비율은 제공하지 않음"));}
    private String summary(int questions,Map<QuestionType,Integer> counts,Contribution c,Compliance compliance,boolean empty) {
        var sb=new StringBuilder();
        if(questions==0) sb.append("AI 질문 기록이 없습니다. ");
        else {
            int good=counts.getOrDefault(QuestionType.CONCEPT_UNDERSTANDING,0)+counts.getOrDefault(QuestionType.RESEARCH,0)+counts.getOrDefault(QuestionType.IDEA_EXPLORATION,0);
            sb.append("AI 질문 ").append(questions).append("건 중 ").append(good).append("건이 개념 이해·자료 탐색·아이디어 구체화 목적이었습니다. ");
        }
        if(empty) sb.append("최종 답안이 비어 있습니다. ");
        else sb.append("최종 답안에서 직접 작성으로 추정되는 비중은 ").append(c.direct()).append("%, AI 답변 사용으로 추정되는 비중은 ").append(c.aiReferenced())
                .append("%입니다. 실제 저자 기여도 비율은 아닙니다. ");
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
    /** direct + aiReferenced = 100, aiUsed는 호환을 위해 항상 0입니다. */
    static Contribution percentages(double aiShare,boolean empty) {
        if(empty) return new Contribution(0,0,0,CONTRIBUTION_BASIS);
        int ai=(int)Math.round(clamp(aiShare)*100);
        if(ai==0&&aiShare>0) ai=1; // AI 문장이 있으면 0%로 숨기지 않습니다.
        if(ai==100&&aiShare<1) ai=99;
        return new Contribution(100-ai,ai,0,CONTRIBUTION_BASIS);
    }
    private static String semanticLimitation(String status) {
        return switch(status==null?"DISABLED":status) {
            case "DONE" -> "바꿔 쓴 문장은 Claude Haiku가 '거의 그대로 따라감'(단계 3)으로 판정했거나, Haiku 단계 2 문장을 Gemini가 단계 3으로 다시 판정한 경우만 AI로 셉니다(SEMANTIC). Gemini도 단계 2로 본 문장은 needsReview로 교사 확인이 필요합니다.";
            case "PARTIAL" -> "Gemini 재판정이 실패해 Haiku 단계 2 문장은 needsReview로만 표시하고 비율에는 넣지 않았습니다.";
            case "PENDING" -> "바꿔 쓴 문장에 대한 LLM 판정이 진행 중입니다.";
            case "UNAVAILABLE" -> "LLM 판정을 사용할 수 없어 글자 비교 결과만 반영했습니다. 단어를 모두 바꿔 쓴 문장은 잡지 못합니다.";
            default -> "단어를 모두 바꿔 쓴 문장은 잡지 못합니다.";
        };
    }
    static String plainText(String html) {
        if(html==null||html.isBlank()) return "";
        return html.matches("(?s).*<\\s*(?:/?[A-Za-z]|!).*")?Jsoup.parseBodyFragment(html).text():html;
    }
    private static List<String> concat(List<String> a,List<String> b) { var out=new ArrayList<>(a);out.addAll(b);return List.copyOf(out); }
    static double clamp(double v) { return Double.isNaN(v)?0:Math.max(0,Math.min(1,v)); }
    static double round(double v,int digits) { double m=Math.pow(10,digits);return Math.round(v*m)/m; }
}
