package kr.lit.api;

import java.util.LinkedHashSet;
import java.util.List;

import static kr.lit.api.ProcessGradingModels.*;

/** Builds the shared teacher and report summary from validated rubric evidence. */
public record ProcessGradingSummary(List<Entry> strengths, List<Entry> weaknesses, List<String> checks) {
    public record Entry(String text, Long conversationId, String quote) {}

    public static ProcessGradingSummary fromResult(Result result) {
        if (result == null) return null;
        var strengths = new LinkedHashSet<Entry>();
        var weaknesses = new LinkedHashSet<Entry>();
        var checks = new LinkedHashSet<String>();
        for (Cell cell : result.cells()) {
            if ("NOT_APPLICABLE".equals(cell.status())) continue;
            Details details = cell.details();
            if (details != null) {
                for (BehaviorReason reason : details.credits()) addEntry(strengths, reason.explanation(), reason.evidence());
                for (BehaviorReason reason : details.deductions()) addEntry(weaknesses, reason.explanation(), reason.evidence());
                details.confirmationNeeded().stream().filter(text -> !text.isBlank()).forEach(checks::add);
            }
            if ("HOLD".equals(cell.status())) {
                checks.add(cell.criterion().displayName() + " 항목의 근거와 단계를 확인해 주세요.");
            }
            if (cell.judge() != null && cell.unmetNextLevel()!=null && !cell.unmetNextLevel().isBlank()) {
                var evidence = cell.counterEvidence().isEmpty()
                        ? cell.conditions().stream().flatMap(condition -> condition.evidence().stream()).toList()
                        : cell.counterEvidence();
                addEntry(weaknesses, cell.unmetNextLevel(), evidence);
            }
        }
        if (strengths.isEmpty() && weaknesses.isEmpty() && checks.isEmpty()) return null;
        return new ProcessGradingSummary(List.copyOf(strengths), List.copyOf(weaknesses), List.copyOf(checks));
    }

    private static void addEntry(LinkedHashSet<Entry> entries, String text, List<Citation> citations) {
        if (text == null || text.isBlank()) return;
        for (Citation citation : citations) {
            if (!isStudentEvidence(citation)) continue;
            entries.add(new Entry(text, citation.location().conversationId(), citation.quote()));
            return;
        }
    }

    private static boolean isStudentEvidence(Citation citation) {
        EvidenceLocation location = citation.location();
        return location != null && ("USER".equals(location.messageRole()) || "FINAL_ANSWER_TEXT".equals(location.textScope()));
    }
}
