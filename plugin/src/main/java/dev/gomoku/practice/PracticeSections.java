package dev.gomoku.practice;

import java.util.List;
import java.util.Set;

final class PracticeSections {
    static final List<String> NAMES = List.of("言语理解", "政治理论", "常识");
    private static final Set<String> GUANGDONG = Set.of(
            "f56c9fa049b76d20ae4dd8b5873d0c0790bdc6eb9cb011f2487eab2bdfa9db81",
            "f73ecd4463b55f3573434a105fb68a24f6a16b1db1fe96a7b7841c45c27762bd");

    // 精确匹配已核对的题库，不更改原文件身份与历史进度。
    static String section(PracticePaper paper, int index) {
        String explicit = paper.questions().get(index).section();
        if (!explicit.isEmpty()) return explicit;
        if (!GUANGDONG.contains(paper.id())) return "";
        if (index < 10) return "政治理论";
        if (index < 15) return "常识";
        if (index < 30) return "言语理解";
        return "";
    }

    // 统计板块题量，选择器仅展示实际存在的板块。
    static int count(PracticePaper paper, String name) {
        int count = 0;
        for (int i = 0; i < paper.questions().size(); i++) if (section(paper, i).equals(name)) count++;
        return count;
    }
}
