package ru.misterparser.futbol24;

import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import org.apache.commons.lang3.tuple.Pair;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ToString
class Match {

    @Getter
    private final LocalDateTime date;

    @Getter
    private final String homeTeam;

    @Getter
    private final String guestTeam;

    @Getter
    private final List<String> categories;

    @Getter
    private final int categoriesOrder;

    private final List<Goal> goals = new ArrayList<>();

    @Getter
    private final Pair<Integer, Integer> scoreHt;

    @Getter
    private final Pair<Integer, Integer> scoreFt;

    @Getter
    private final String url;

    Match(LocalDateTime date, String homeTeam, String guestTeam, List<String> categories, int categoriesOrder, Pair<Integer, Integer> scoreHt, Pair<Integer, Integer> scoreFt, String url) {
        this.date = date;
        this.homeTeam = homeTeam;
        this.guestTeam = guestTeam;
        this.categories = List.copyOf(categories);
        this.categoriesOrder = categoriesOrder;
        this.scoreHt = scoreHt;
        this.scoreFt = scoreFt;
        this.url = url;
    }

    void addGoal(Goal goal) {
        goals.add(goal);
    }

    List<Goal> getGoals() {
        return Collections.unmodifiableList(goals);
    }

    @Data
    static class Goal {
        @NonNull
        private final Type type;
        private final String minute;
        private final Pair<Integer, Integer> scoreBefore;
        private final String team;
        private final String player;

        enum Type {
            REGULAR(1),
            RED_CARD(2),
            YELLOW_CARD(3),
            OWN_GOAL(4),
            PENALTY_HOT(5),
            UNBEATEN_PENALTY(6);

            private static final Pattern PATTERN = Pattern.compile("[gh]action([1-6])");
            private static final Map<Integer, Type> CODE_TO_TYPE = new LinkedHashMap<>();

            private int type;

            Type(int type) {
                this.type = type;
            }

            static Type fromCss(String style) {
                Type type = null;
                Matcher matcher = PATTERN.matcher(style);
                if (matcher.find()) {
                    int c = Integer.parseInt(matcher.group(1));
                    type = CODE_TO_TYPE.get(c);
                }
                return type;
            }

            static {
                for (Type type : Type.values())
                    CODE_TO_TYPE.put(type.type, type);
            }
        }
    }
}
