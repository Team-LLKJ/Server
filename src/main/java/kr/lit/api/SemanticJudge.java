package kr.lit.api;

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
 * 답안 출처 판정 2단계: 글자 비교로 DIRECT가 된 문장 중 바꿔 쓴 문장을 LLM으로 판정합니다(개선안 11월 10일 수정본).
 * <ol>
 *   <li>후보 고르기(plan): 1단계 DIRECT, 정규화 15자 이상, 학생이 AI에게 질문한 문항의 문장만. 후보는 해당 문항 AI 문장 전체(최대 8개).</li>
 *   <li>Gemini가 모든 후보 문장에 단계(0~3)와 closestCandidate를 매깁니다.</li>
 *   <li>Gemini 단계 3·2, 그리고 단계 1 중 가까운 AI 문장과 유사도 상위 20%만 Sonnet이 같은 프롬프트로 따로 판정합니다.</li>
 *   <li>두 모델이 모두 단계 3이고 같은 AI 문장을 골랐을 때만 AI(SEMANTIC). 한쪽만 3이면 needsReview.</li>
 * </ol>
 * 판정은 (모델, 프롬프트 버전, 문항, 학생 문장, 후보 문장) 해시로 저장해 같은 문장은 다시 호출하지 않습니다. 원문은 저장하지 않습니다.
 */
@Component
public class SemanticJudge {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SemanticJudge.class);
    public static final String GEMINI="GEMINI",ANTHROPIC="ANTHROPIC";
    public static final String STATUS_DISABLED="DISABLED",STATUS_PENDING="PENDING",STATUS_DONE="DONE",STATUS_PARTIAL="PARTIAL",STATUS_UNAVAILABLE="UNAVAILABLE";
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
        out.put("geminiModel",config.getGemini().getModel());out.put("anthropicModel",config.getAnthropic().getModel());
        out.put("level1ReviewShare",config.getLevel1ReviewShare());out.put("maxCandidates",config.getMaxCandidates());out.put("minSentenceLength",config.getMinSentenceLength());
        return out;
    }

    // ---------- 후보 고르기 ----------
    public Plan plan(JsonNode source,Analysis stage1) {
        if(!isActive()||stage1==null||stage1.sentences()==null) return Plan.EMPTY;
        var questions=new HashMap<String,JsonNode>();
        for(var entry:source.path("answers")) questions.put(entry.path("question").path("id").asText(),entry.path("question"));
        var aiSentences=new HashMap<String,List<Candidate>>();
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
            items.add(new PlanItem(key,sentence.questionId(),excluded,new Item(sentence.id(),questionText.strip(),sentence.text(),candidates)));
        }
        return new Plan(List.copyOf(items));
    }

    // ---------- 호출(백그라운드 전용) ----------
    /** 캐시에 없는 판정만 호출해 저장합니다. 실패한 단계(GEMINI/ANTHROPIC) 또는 null을 돌려줍니다. 화면 요청 경로에서는 부르지 않습니다. */
    public String run(Plan plan) {
        if(!isActive()||plan.items().isEmpty()) return null;
        var gemini=load(plan,GEMINI,client.geminiModel());
        var missing=plan.items().stream().filter(p->!gemini.containsKey(p.key())).toList();
        try { call(missing,GEMINI,client.geminiModel(),gemini); }
        catch(JudgeFailure ex) { log.warn("Semantic judge Gemini call failed: {}",ex.code); return GEMINI; }
        var targets=sonnetTargets(plan,gemini);
        if(targets.isEmpty()) return null;
        if(!client.anthropicConfigured()) return ANTHROPIC;
        var sonnet=load(plan,ANTHROPIC,client.anthropicModel());
        var missingSonnet=plan.items().stream().filter(p->targets.contains(p.key())&&!sonnet.containsKey(p.key())).toList();
        try { call(missingSonnet,ANTHROPIC,client.anthropicModel(),sonnet); }
        catch(JudgeFailure ex) { log.warn("Semantic judge Anthropic call failed: {}",ex.code); return ANTHROPIC; }
        return null;
    }
    private void call(List<PlanItem> items,String provider,String model,Map<String,Judgment> into) {
        for(int from=0;from<items.size();from+=BATCH_SIZE) {
            var batch=items.subList(from,Math.min(items.size(),from+BATCH_SIZE));
            var request=batch.stream().map(PlanItem::item).toList();
            var results=provider.equals(GEMINI)?client.judgeWithGemini(request):client.judgeWithAnthropic(request);
            for(var p:batch) {
                var j=results.get(p.item().id());
                db.update("MERGE INTO semantic_judgments(cache_key,provider,model,prompt_version,judge_level,closest_candidate,created_at) KEY(cache_key) VALUES (?,?,?,?,?,?,?)",
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
        var gemini=load(plan,GEMINI,client.geminiModel());
        if(gemini.size()<plan.items().size()) // Gemini가 실패하면 1단계 결과만 씁니다.
            return new SemanticInput(GEMINI.equals(failure)?STATUS_UNAVAILABLE:STATUS_PENDING,Map.of());
        var targets=sonnetTargets(plan,gemini);
        var sonnet=targets.isEmpty()?Map.<String,Judgment>of():load(plan,ANTHROPIC,client.anthropicModel());
        boolean sonnetMissing=targets.stream().anyMatch(k->!sonnet.containsKey(k));
        boolean sonnetFailed=sonnetMissing&&(ANTHROPIC.equals(failure)||GEMINI.equals(failure));
        if(sonnetMissing&&!sonnetFailed) return new SemanticInput(STATUS_PENDING,Map.of());
        var verdicts=new HashMap<String,SemanticVerdict>();
        for(var p:plan.items()) {
            var g=gemini.get(p.key());boolean sent=targets.contains(p.key());
            verdicts.put(p.key(),decide(g,sent?sonnet.get(p.key()):null,sent,p.item().candidates()));
        }
        return new SemanticInput(sonnetFailed?STATUS_PARTIAL:STATUS_DONE,verdicts);
    }

    /**
     * 개선안 표 그대로:
     * G3→S3(같은 AI 문장)=AI, G3→S≤2 또는 Sonnet 실패=직접+확인, G2→S3=직접+확인, G2→S≤2=직접,
     * G1(유사도 높음)→S3=직접+확인, 그 외=직접.
     */
    static SemanticVerdict decide(Judgment gemini,Judgment sonnet,boolean sent,List<Candidate> candidates) {
        boolean ai=false,review=false;
        if(gemini.level()==3) {
            if(sent&&sonnet!=null&&sonnet.level()==3&&gemini.closestCandidate()!=null&&gemini.closestCandidate().equals(sonnet.closestCandidate())) ai=true;
            else review=true;
        } else if(sent&&sonnet!=null&&sonnet.level()==3) review=true;
        var closest=candidates.stream().filter(c->c.id().equals(gemini.closestCandidate())).findFirst().orElse(null);
        return new SemanticVerdict(gemini.level(),sent&&sonnet!=null?sonnet.level():null,closest==null?null:closest.id(),
                closest==null?null:closest.text(),closest==null?null:closest.conversationId(),ai,review);
    }

    /** Sonnet에 보낼 문장: Gemini 단계 3·2, 단계 1 중 closestCandidate와 2글자 조각 유사도 상위 level1ReviewShare. */
    Set<String> sonnetTargets(Plan plan,Map<String,Judgment> gemini) {
        var targets=new LinkedHashSet<String>();
        record Scored(String key,double similarity) {}
        var level1=new ArrayList<Scored>();
        for(var p:plan.items()) {
            var g=gemini.get(p.key());if(g==null) continue;
            if(g.level()>=2) targets.add(p.key());
            else if(g.level()==1) {
                var closest=p.item().candidates().stream().filter(c->c.id().equals(g.closestCandidate())).findFirst().orElse(null);
                double similarity=closest==null?0:SentenceSimilarity.bigramContainment(p.item().student(),closest.text(),p.excluded());
                if(similarity>0) level1.add(new Scored(p.key(),similarity));
            }
        }
        int count=(int)Math.ceil(level1.size()*config.getLevel1ReviewShare());
        level1.sort(Comparator.comparingDouble(Scored::similarity).reversed().thenComparing(Scored::key));
        for(int i=0;i<count&&i<level1.size();i++) targets.add(level1.get(i).key());
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
                    p.item().candidates().stream().map(c->c.id()+"\u0001"+c.text()).toList()));
            var digest=MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
}
