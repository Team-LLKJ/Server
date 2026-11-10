package kr.lit.api;

import java.util.*;

/** 미분류를 제외한 관측된 분류만 0.1% 단위 최대 잔여 방식으로 합계100%를 구성합니다. */
final class UsageDistribution {
    private UsageDistribution() {}
    static List<ReportModels.Usage> calculate(Map<String,Integer> input) {
        var entries=input.entrySet().stream().filter(e->!e.getKey().equals("UNCLASSIFIED")&&e.getValue()>0).sorted(Map.Entry.comparingByKey()).toList();int total=entries.stream().mapToInt(Map.Entry::getValue).sum();if(total==0)return List.of();
        int[] units=new int[entries.size()];int sum=0;for(int i=0;i<units.length;i++){units[i]=(int)((long)entries.get(i).getValue()*1000/total);sum+=units[i];}
        var order=new ArrayList<Integer>();for(int i=0;i<units.length;i++)order.add(i);order.sort(Comparator.<Integer>comparingLong(i->-((long)entries.get(i).getValue()*1000%total)).thenComparingInt(i->i));for(int i=0;i<1000-sum;i++)units[order.get(i)]++;
        var out=new ArrayList<ReportModels.Usage>();for(int i=0;i<units.length;i++)out.add(new ReportModels.Usage(label(entries.get(i).getKey()),units[i]/10.0,units[i]>=500?"높음":units[i]>=200?"보통":"낮음","blue"));return List.copyOf(out);
    }
    private static String label(String key){return switch(key){case "IDEATION"->"아이디어 탐색";case "RESEARCH"->"자료 조사";case "STRUCTURING"->"구조화";case "VERIFICATION"->"검증";case "REVISION"->"답안 수정";case "ANSWER_DRAFTING"->"답안 생성 요청";case "CONCEPT_HELP"->"개념 도움";default->"기타";};}
}
