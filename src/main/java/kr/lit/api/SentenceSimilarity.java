package kr.lit.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 답안 출처 판정 V2의 문장 단위 비교. 문항·평가 안내에도 있는 표현은 3-gram 포함률과 긴 일치 구간 모두에서 제외합니다.
 * DB·외부 호출이 없는 순수 계산입니다.
 */
final class SentenceSimilarity {
    /** overlap: 문항 문구를 뺀 학생 3-gram 중 AI 답변에도 있는 비율. longestSpan: 정규화 후 AI 답변과 이어서 같은 가장 긴 구간 길이. */
    record Match(double overlap, int longestSpan) {
        static final Match NONE = new Match(0, 0);
    }

    /** 미리 계산한 비교 대상(AI 답변 하나). 문장마다 다시 쪼개지 않도록 한 번만 만듭니다. */
    static final class Reference {
        private final Set<String> grams;
        private final Set<String> spans;
        private final int spanLength;

        Reference(String text, int spanLength) {
            if (spanLength < 1) throw new IllegalArgumentException("spanLength must be positive");
            String normalized = AssessmentAnalyzer.normalize(text);
            this.grams = windows(normalized, 3);
            this.spans = windows(normalized, spanLength);
            this.spanLength = spanLength;
        }

        static Reference merged(List<String> texts, int spanLength) {
            var reference = new Reference("", spanLength);
            for (String text : texts) {
                String normalized = AssessmentAnalyzer.normalize(text);
                reference.grams.addAll(windows(normalized, 3));
                reference.spans.addAll(windows(normalized, spanLength));
            }
            return reference;
        }
    }

    /** 비교에서 뺄 문항·평가 안내 문구. */
    static final class Exclusion {
        private final String normalized;
        private final Set<String> grams;
        private final java.util.Map<Integer, Set<String>> spansByLength = new java.util.HashMap<>();

        Exclusion(String prompt) {
            this.normalized = AssessmentAnalyzer.normalize(prompt);
            this.grams = windows(normalized, 3);
        }

        private Exclusion(String normalized, Set<String> grams) {
            this.normalized = normalized;
            this.grams = grams;
        }

        /**
         * 학생이 먼저 말한 표현을 더한 제외 목록. AI가 학생 질문을 되풀이한 3-gram·긴 구간은 학생 것이므로 비교에서 뺍니다.
         * studentGrams·studentSpans는 {@link StudentOrigin}이 만든 값(앞선 AI 답변에서 옮긴 표현은 이미 빠져 있음).
         */
        Exclusion withStudentOrigin(StudentOrigin origin, int spanLength) {
            if (origin == null || origin.isEmpty()) return this;
            var grams = new HashSet<>(this.grams);
            grams.addAll(origin.grams);
            var out = new Exclusion(normalized, grams);
            var spans = new HashSet<>(spans(spanLength));
            spans.addAll(origin.spans);
            out.spansByLength.put(spanLength, spans);
            return out;
        }

        private Set<String> spans(int length) {
            return spansByLength.computeIfAbsent(length, n -> windows(normalized, n));
        }
    }

    /**
     * 학생이 대화에서 먼저 말한 표현(질문의 3-gram·spanLength 구간). 출처는 대화에서 처음 나온 사람으로 봅니다:
     * 학생 질문에 먼저 나오면 학생 것, 그 질문보다 앞선 AI 답변에 이미 있던 표현을 학생이 옮겼으면 AI 것(여기서 빠짐).
     */
    static final class StudentOrigin {
        static final StudentOrigin EMPTY = new StudentOrigin(Set.of(), Set.of());
        private final Set<String> grams;
        private final Set<String> spans;

        private StudentOrigin(Set<String> grams, Set<String> spans) {
            this.grams = grams;
            this.spans = spans;
        }

        boolean isEmpty() { return grams.isEmpty() && spans.isEmpty(); }
        boolean containsGram(String gram) { return grams.contains(gram); }

        /**
         * 대화를 시간 순서(question, answer)로 받아, 각 대화 시점까지 학생이 먼저 말한 표현을 돌려줍니다(index i = i번째 대화의 답변 시점).
         * answer가 null이면(응답 없음) AI 표현에 더하지 않습니다.
         */
        static List<StudentOrigin> chronological(List<String> questions, List<String> answers, int spanLength) {
            var out = new ArrayList<StudentOrigin>();
            var aiGrams = new HashSet<String>();
            var aiSpans = new HashSet<String>();
            var grams = new HashSet<String>();
            var spans = new HashSet<String>();
            for (int i = 0; i < questions.size(); i++) {
                String question = AssessmentAnalyzer.normalize(questions.get(i) == null ? "" : questions.get(i));
                for (String gram : windows(question, 3)) if (!aiGrams.contains(gram)) grams.add(gram);
                for (String span : windows(question, spanLength)) if (!aiSpans.contains(span)) spans.add(span);
                out.add(new StudentOrigin(Set.copyOf(grams), Set.copyOf(spans)));
                String answer = i < answers.size() ? answers.get(i) : null;
                if (answer != null) {
                    String normalized = AssessmentAnalyzer.normalize(answer);
                    aiGrams.addAll(windows(normalized, 3));
                    aiSpans.addAll(windows(normalized, spanLength));
                }
            }
            return out;
        }
    }

    private SentenceSimilarity() {}

    static Match compare(String sentence, List<String> answers, String prompt, int spanLength) {
        return compare(sentence, Reference.merged(answers, spanLength), new Exclusion(prompt));
    }

    static Match compare(String sentence, Reference ai, Exclusion excluded) {
        String text = AssessmentAnalyzer.normalize(sentence);
        if (text.isEmpty()) return Match.NONE;
        Set<String> studentGrams = windows(text, 3);
        studentGrams.removeAll(excluded.grams);
        long hits = studentGrams.stream().filter(ai.grams::contains).count();
        double overlap = studentGrams.isEmpty() ? 0 : (double) hits / studentGrams.size();

        Set<String> excludedSpans = excluded.spans(ai.spanLength);
        boolean[] matched = new boolean[text.length()];
        for (int i = 0; i + ai.spanLength <= text.length(); i++) {
            String window = text.substring(i, i + ai.spanLength);
            if (ai.spans.contains(window) && !excludedSpans.contains(window)) {
                Arrays.fill(matched, i, i + ai.spanLength, true);
            }
        }
        int longest = 0;
        int current = 0;
        for (boolean value : matched) {
            current = value ? current + 1 : 0;
            longest = Math.max(longest, current);
        }
        return new Match(overlap, longest);
    }

    /** Returns the strongest matching sentence in the selected original AI response. */
    static AssessmentModels.AiAnswerMatch findAnswerMatch(String student,String answer,Exclusion excluded,int minSpan) {
        if (answer==null) return null;
        Span best=null;
        Match bestMatch=Match.NONE;
        for (Span candidate : sentences(answer)) {
            Match match=compare(student,new Reference(answer.substring(candidate.start(),candidate.end()),minSpan),excluded);
            if (match.longestSpan()>bestMatch.longestSpan()
                    || (match.longestSpan()==bestMatch.longestSpan()&&match.overlap()>bestMatch.overlap())) {
                best=candidate;
                bestMatch=match;
            }
        }
        return best==null?null:new AssessmentModels.AiAnswerMatch(best.start(),best.end(),answer.substring(best.start(),best.end()));
    }

    /** 2단계 후보 고르기용: 문항 문구를 뺀 학생 문장 2글자 조각 중 후보 문장에도 있는 비율. */
    static double bigramContainment(String student, String candidate, String excluded) {
        Set<String> grams = windows(AssessmentAnalyzer.normalize(student), 2);
        grams.removeAll(windows(AssessmentAnalyzer.normalize(excluded), 2));
        if (grams.isEmpty()) return 0;
        Set<String> target = windows(AssessmentAnalyzer.normalize(candidate), 2);
        return (double) grams.stream().filter(target::contains).count() / grams.size();
    }

    /** 문장 하나의 위치(문단 문자열 기준 UTF-16, end 미포함). */
    record Span(int start, int end) {}

    /**
     * 마침표·물음표·느낌표(연속 포함, 뒤따르는 닫는 따옴표·괄호 포함) 뒤에 공백이 오거나 문단이 끝나면 문장을 나눕니다.
     * "3.14"처럼 기호 뒤에 공백이 없으면 나누지 않습니다. 앞뒤 공백은 문장 범위에서 뺍니다.
     */
    static List<Span> sentences(String paragraph) {
        var out = new ArrayList<Span>();
        if (paragraph == null || paragraph.isEmpty()) return out;
        int start = 0;
        int n = paragraph.length();
        int i = 0;
        while (i < n) {
            char ch = paragraph.charAt(i);
            if (isTerminal(ch)) {
                int end = i + 1;
                while (end < n && (isTerminal(paragraph.charAt(end)) || isCloser(paragraph.charAt(end)))) end++;
                if (end == n || Character.isWhitespace(paragraph.charAt(end))) {
                    add(out, paragraph, start, end);
                    start = end;
                }
                i = end;
            } else {
                i++;
            }
        }
        add(out, paragraph, start, n);
        return out;
    }

    private static void add(List<Span> out, String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end > start) out.add(new Span(start, end));
    }

    private static boolean isTerminal(char ch) {
        return ch == '.' || ch == '?' || ch == '!' || ch == '。' || ch == '？' || ch == '！' || ch == '…';
    }

    private static boolean isCloser(char ch) {
        return ch == '"' || ch == '\'' || ch == ')' || ch == ']' || ch == '”' || ch == '’' || ch == '」' || ch == '』' || ch == '）';
    }

    private static Set<String> windows(String text, int size) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + size <= text.length(); i++) {
            result.add(text.substring(i, i + size));
        }
        return result;
    }
}
