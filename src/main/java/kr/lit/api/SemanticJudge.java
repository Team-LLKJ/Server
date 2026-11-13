package kr.lit.api;

import kr.lit.db.DatabaseUpsert;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static kr.lit.api.AssessmentModels.*;
import static kr.lit.api.SemanticJudgeClient.*;

/**
 * 답안 출처 판정 2단계: 글자 비교로 DIRECT가 된 문장 중 바꿔 쓴 문장을 LLM으로 판정합니다(Haiku 먼저 → 애매한 것만 Gemini).
 * <ol>
 *   <li>후보 고르기(plan): 1단계 DIRECT, 정규화 15자 이상, 학생이 AI에게 질문한 문항의 문장만. 후보는 해당 문항 AI 문장(최대 8개).</li>
 *   <li>제출 1건의 대상 문장을 묶어 Claude Haiku에 1번 호출합니다. 단계(0~3)와 closestCandidate를 받습니다.</li>
 *   <li>Haiku가 단계 2를 준 문장만 묶어 Gemini에 1번 호출합니다(Haiku 결과는 알려 주지 않음). 애매함은 모델이 말하는 confidence가 아니라 단계 2로 고릅니다.</li>
 *   <li>Haiku 3 → AI(SEMANTIC). Haiku 2 → Gemini 3이면 AI, 2면 직접+needsReview, 0~1이면 직접. Haiku 0~1 → 직접.</li>
 * </ol>
 * 판정은 (모델, 프롬프트 버전, 문항, 학생 문장, 후보 문장) 해시로 저장해 같은 문장은 다시 호출하지 않습니다. 원문은 저장하지 않습니다.
 */
@Component
public class SemanticJudge {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SemanticJudge.class);
    public static final String ANTHROPIC="ANTHROPIC",GEMINI="GEMINI";
    public static final String STATUS_DISABLED="DISABLED",STATUS_PENDING="PENDING",STATUS_DONE="DONE",STATUS_PARTIAL="PARTIAL",STATUS_UNAVAILABLE="UNAVAILABLE";
    /** 판정이 일부·전부 실패(PARTIAL/UNAVAILABLE)하면 1분 뒤 다시 시도합니다. 최대 시도 횟수(첫 시도 포함). */
    public static final int MAX_ATTEMPTS=3;
    public static final java.time.Duration RETRY_AFTER=java.time.Duration.ofMinutes(1);
    private static final int BATCH_SIZE=50;

    public record PlanItem(String key,String questionId,String excluded,Item item) {}
    public record Plan(List<PlanItem> items) {
        public static final Plan EMPTY=new Plan(List.of());
    }

    private final SemanticJudgeConfig config;
    private final SemanticJudgeClient client;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public SemanticJudge(SemanticJudgeConfig config,SemanticJudgeClient client,JdbcTemplate db,ObjectMapper json) {
        this.config=config;this.client=client;this.db=db;this.json=json;
    }
    public boolean isActive(){return config.isActive();}
    /** 교사 판정 수정 기록에 함께 저장할 2단계 설정(보정용). */
    public Map<String,Object> describe() {
        var out=new LinkedHashMap<String,Object>();
        out.put("judgeActive",isActive());out.put("promptVersion",SemanticJudgeConfig.PROMPT_VERSION);
        out.put("haikuModel",config.getAnthropic().getModel());out.put("geminiModel",config.getGemini().getModel());
        out.put("maxCandidates",config.getMaxCandidates());out.put("minSentenceLength",config.getMinSentenceLength());
        return out;
    }

    // ---------- 후보 고르기 ----------
    public Plan plan(JsonNode source,Analysis stage1) {
        if(!isActive()||stage1==null||stage1.sentences()==null) return Plan.EMPTY;
        var questions=new HashMap<String,JsonNode>();
        for(var entry:source.path("answers")) questions.put(entry.path("question").path("id").asText(),entry.path("question"));
        var aiSentences=new HashMap<String,List<Candidate>>();
        // 문항별 학생 질문(시간 순): AI가 학생 질문을 되풀이한 부분은 학생 것으로 보게 프롬프트에 함께 넣습니다.
        var studentQuestions=new HashMap<String,List<String>>();
        var orderedTurns=new ArrayList<JsonNode>();source.path("conversations").forEach(orderedTurns::add);
        orderedTurns.sort(Comparator.comparingLong(t->t.path("id").asLong()));
        for(var turn:orderedTurns) { String text=turn.path("questionText").asText("").strip();if(!text.isEmpty()) studentQuestions.computeIfAbsent(turn.path("questionId").asText(),k->new ArrayList<>()).add(text); }
        for(var turn:source.path("conversations")) {
            if(!"COMPLETED".equals(turn.path("state").asText())||turn.path("answerText").isNull()) continue;
            String qid=turn.path("questionId").asText();long id=turn.path("id").asLong();int index=0;
            var list=aiSentences.computeIfAbsent(qid,k->new ArrayList<>());
            for(String paragraph:AssessmentAnalyzer.paragraphs(turn.path("answerText").asText(""))) {
                for(var range:SentenceSimilarity.sentences(paragraph)) {
                    String text=paragraph.substring(range.start(),range.end());
                    if(AssessmentAnalyzer.normalize(text).length()<5) continue;
                    list.add(new Candidate("c"+id+"-"+(++index),id,text));
                }
            }
        }
        var items=new ArrayList<PlanItem>();var seen=new HashSet<String>();
        for(var sentence:stage1.sentences()) {
            if(!AssessmentAnalyzer.SOURCE_DIRECT.equals(sentence.source())) continue;
            if(AssessmentAnalyzer.normalize(sentence.text()).length()<config.getMinSentenceLength()) continue;
            var pool=aiSentences.getOrDefault(sentence.questionId(),List.of());
            var question=questions.get(sentence.questionId());
            if(pool.isEmpty()||question==null) continue; // 학생이 AI에게 질문하지 않은 문항은 건너뜁니다.
            String key=AssessmentAnalyzer.sentenceKey(sentence.questionId(),sentence.text());
            if(!seen.add(key)) continue;
            String excluded=AssessmentAnalyzer.excludedText(source,question);
            List<Candidate> candidates=pool;
            if(pool.size()>config.getMaxCandidates()) {
                var ranked=new ArrayList<>(pool);
                ranked.sort(Comparator.comparingDouble((Candidate c)->-SentenceSimilarity.bigramContainment(sentence.text(),c.text(),excluded)));
                candidates=List.copyOf(ranked.subList(0,config.getMaxCandidates()));
            }
            String questionText=question.path("title").asText("")+"\n"+AssessmentAnalyzer.plainText(question.path("content").asText(""));
            items.add(new PlanItem(key,sentence.questionId(),excluded,new Item(sentence.id(),questionText.strip(),sentence.text(),candidates,studentQuestions.getOrDefault(sentence.questionId(),List.of()))));
        }
        return new Plan(List.copyOf(items));
    }

    // ---------- 호출(백그라운드 전용) ----------
    /** 캐시에 없는 판정만 호출해 저장합니다. 실패한 단계(ANTHROPIC/GEMINI) 또는 null을 돌려줍니다. 화면 요청 경로에서는 부르지 않습니다. */
    public String run(Plan plan) {
        if(!isActive()||plan.items().isEmpty()) return null;
        var haiku=load(plan,ANTHROPIC,client.anthropicModel());
        var missing=plan.items().stream().filter(p->!haiku.containsKey(p.key())).toList();
        try { call(missing,ANTHROPIC,client.anthropicModel(),haiku); }
        catch(JudgeFailure ex) { log.warn("Semantic judge Haiku call failed: {}",ex.code); return ANTHROPIC; }
        var targets=geminiTargets(plan,haiku);
        if(targets.isEmpty()) return null;
        if(!client.geminiConfigured()) return GEMINI;
        var gemini=load(plan,GEMINI,client.geminiModel());
        var missingGemini=plan.items().stream().filter(p->targets.contains(p.key())&&!gemini.containsKey(p.key())).toList();
        try { call(missingGemini,GEMINI,client.geminiModel(),gemini); }
        catch(JudgeFailure ex) { log.warn("Semantic judge Gemini call failed: {}",ex.code); return GEMINI; }
        return null;
    }
    private void call(List<PlanItem> items,String provider,String model,Map<String,Judgment> into) {
        for(int from=0;from<items.size();from+=BATCH_SIZE) {
            var batch=items.subList(from,Math.min(items.size(),from+BATCH_SIZE));
            var request=batch.stream().map(PlanItem::item).toList();
            var results=provider.equals(ANTHROPIC)?client.judgeWithHaiku(request):client.judgeWithGemini(request);
            for(var p:batch) {
                var j=results.get(p.item().id());
                DatabaseUpsert.save(db,"semantic_judgments","cache_key,provider,model,prompt_version,judge_level,closest_candidate,created_at","cache_key","?,?,?,?,?,?,?",
                        cacheKey(provider,model,p),provider,model,SemanticJudgeConfig.PROMPT_VERSION,j.level(),j.closestCandidate(),OffsetDateTime.now(ZoneOffset.UTC));
                into.put(p.key(),j);
            }
        }
    }

    // ---------- 저장된 판정 → analyze() 입력 ----------
    /** failure: 이번 백그라운드 실행에서 실패한 단계(없으면 null). 화면 요청 경로는 null로 부르고 캐시만 읽습니다. */
    public SemanticInput cached(Plan plan,String failure) {
        if(!isActive()) return SemanticInput.DISABLED;
        if(plan.items().isEmpty()) return new SemanticInput(STATUS_DONE,Map.of());
        var haiku=load(plan,ANTHROPIC,client.anthropicModel());
        if(haiku.size()<plan.items().size()) // Haiku가 실패하면 1단계 결과만 씁니다.
            return new SemanticInput(ANTHROPIC.equals(failure)?STATUS_UNAVAILABLE:STATUS_PENDING,Map.of());
        var targets=geminiTargets(plan,haiku);
        var gemini=targets.isEmpty()?Map.<String,Judgment>of():load(plan,GEMINI,client.geminiModel());
        boolean geminiMissing=targets.stream().anyMatch(k->!gemini.containsKey(k));
        boolean geminiFailed=geminiMissing&&failure!=null;
        if(geminiMissing&&!geminiFailed) return new SemanticInput(STATUS_PENDING,Map.of());
        var verdicts=new HashMap<String,SemanticVerdict>();
        for(var p:plan.items())
            verdicts.put(p.key(),decide(haiku.get(p.key()),gemini.get(p.key()),targets.contains(p.key()),p.item().candidates()));
        return new SemanticInput(geminiFailed?STATUS_PARTIAL:STATUS_DONE,verdicts);
    }

    /**
     * 판정표: Haiku 3=AI. Haiku 2 → Gemini 3=AI, Gemini 2=직접+needsReview, Gemini 0~1=직접, Gemini 실패=직접+needsReview.
     * Haiku 0~1=직접(Gemini에 보내지 않음).
     */
    static SemanticVerdict decide(Judgment haiku,Judgment gemini,boolean sent,List<Candidate> candidates) {
        boolean ai=false,review=false;String closestId=haiku.closestCandidate();
        if(haiku.level()==3) ai=true;
        else if(haiku.level()==2&&sent) {
            if(gemini==null) review=true;
            else if(gemini.level()==3) { ai=true;if(gemini.closestCandidate()!=null) closestId=gemini.closestCandidate(); }
            else if(gemini.level()==2) review=true;
        }
        String chosen=closestId;
        var closest=candidates.stream().filter(c->c.id().equals(chosen)).findFirst().orElse(null);
        return new SemanticVerdict(haiku.level(),sent&&gemini!=null?gemini.level():null,closest==null?null:closest.id(),
                closest==null?null:closest.text(),closest==null?null:closest.conversationId(),ai,review);
    }

    /** Gemini에 보낼 문장: Haiku가 단계 2를 준 문장만. */
    static Set<String> geminiTargets(Plan plan,Map<String,Judgment> haiku) {
        var targets=new LinkedHashSet<String>();
        for(var p:plan.items()) { var h=haiku.get(p.key());if(h!=null&&h.level()==2) targets.add(p.key()); }
        return targets;
    }

    private Map<String,Judgment> load(Plan plan,String provider,String model) {
        var out=new HashMap<String,Judgment>();
        for(var p:plan.items()) {
            var rows=db.query("SELECT judge_level,closest_candidate FROM semantic_judgments WHERE cache_key=?",
                    (r,n)->new Judgment(r.getInt("judge_level"),r.getString("closest_candidate")),cacheKey(provider,model,p));
            if(!rows.isEmpty()) out.put(p.key(),rows.get(0));
        }
        return out;
    }
    String cacheKey(String provider,String model,PlanItem p) {
        try {
            var material=json.writeValueAsString(List.of(provider,model,SemanticJudgeConfig.PROMPT_VERSION,p.item().question(),p.item().student(),
                    p.item().candidates().stream().map(c->c.id()+"\u0001"+c.text()).toList(),p.item().studentQuestions()));
            var digest=MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
}
