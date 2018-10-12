package ru.misterparser.futbol24;

import org.apache.commons.lang3.StringUtils;
import ru.misterparser.common.Utils;
import ru.misterparser.common.sp.SpUtils;

import java.util.List;

class FilterUtils {

    static boolean checkGoalByMinutes(Match.Goal goal) {
        for (String minute : Configuration.get().MINUTES) {
            if (checkGoalByMinute(goal, minute)) {
                return true;
            }
        }
        return false;
    }

    static boolean checkGoalByMinute(Match.Goal goal, String minute) {
        if (minute.matches("[0-9]+-[0-9]+")) {
            List<String> minutes = SpUtils.getSizeLine(minute, 1);
            return minutes.contains(goal.getMinute());
        } else if (StringUtils.containsIgnoreCase(minute, "+")) {
            return StringUtils.startsWithIgnoreCase(goal.getMinute(), minute);
        } else {
            return StringUtils.equalsIgnoreCase(goal.getMinute(), minute);
        }
    }

    static boolean checkMinuteFilter(Match match) {
        boolean minuteFilter = Configuration.get().MINUTES.isEmpty();
        for (Match.Goal goal : match.getGoals()) {
            if (FilterUtils.checkGoalByMinutes(goal)) {
                minuteFilter = true;
                break;
            }
        }
        return minuteFilter;
    }

    static boolean checkByTotal(Match.Goal goal, String totalRange) {
        int total = goal.getScoreBefore().getLeft() + goal.getScoreBefore().getRight();
        try {
            return Utils.checkInRange(total + "", totalRange, false);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean checkBySubtract(Match.Goal goal, String subtractRange) {
        int subtract = Math.abs(goal.getScoreBefore().getLeft() - goal.getScoreBefore().getRight());
        return Utils.checkInRange(subtract + "", subtractRange, false);
    }
}
