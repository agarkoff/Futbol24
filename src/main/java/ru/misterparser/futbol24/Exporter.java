package ru.misterparser.futbol24;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.misterparser.common.Utils;
import ru.misterparser.common.collection.ArrayListValuedLinkedHashMap;

import java.io.FileOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
class Exporter {

    private static final DateTimeFormatter SHEET_NAME_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH_mm");

    private final String filename;
    private final List<Match> matches;
    private final Map<List<String>, List<String>> noInfoUrls;

    private int rowIndex;

    Exporter(String filename, List<Match> matches, Map<List<String>, List<String>> noInfoUrls) {
        this.filename = filename;
        this.matches = new ArrayList<>(matches);
        this.noInfoUrls = noInfoUrls;
    }

    void save() {
        try (FileOutputStream fout = new FileOutputStream(filename);
             Workbook workbook = new XSSFWorkbook()) {
            String sheetName = LocalDateTime.now().format(SHEET_NAME_DATE_TIME_FORMATTER);
            sheetName = sheetName.replace(":", "");
            Sheet sheet = workbook.createSheet(sheetName);
            save(sheet);
            workbook.write(fout);
        } catch (Exception e) {
            log.debug("Exception", e);
        }
    }

    private void save(Sheet sheet) {
        log.debug("Сохранение листа: " + sheet.getSheetName());
        ArrayListValuedLinkedHashMap<List<String>, Match> matchesByCategories = new ArrayListValuedLinkedHashMap<>();
        matches.sort(Comparator.comparingInt(Match::getCategoriesOrder));
        for (Match match : matches) {
            matchesByCategories.put(match.getCategories(), match);
        }
        log.debug("По минутам отфильтровано матчей: " + matchesByCategories.values().size());
        writeHeader(sheet);
        for (Map.Entry<List<String>, Collection<Match>> entry : matchesByCategories.asMap().entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            writeRow(row, entry);
        }
    }

    private void writeHeader(Sheet sheet) {
        Row row = sheet.createRow(rowIndex++);
        int columnIndex = 0;
        row.createCell(columnIndex++).setCellValue("Страна");
        row.createCell(columnIndex++).setCellValue("Лига");
        row.createCell(columnIndex++).setCellValue("Сезон");
        if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.TOTAL) {
            for (String ignored : Configuration.get().TOTALS) {
                row.createCell(columnIndex++).setCellValue("Тотал");
                for (String minute : Configuration.get().MINUTES) {
                    row.createCell(columnIndex++).setCellValue(minute);
                }
                row.createCell(columnIndex++).setCellValue("Итого");
            }
        } else if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.SUBTRACT) {
            for (String ignored : Configuration.get().SUBTRACTS) {
                row.createCell(columnIndex++).setCellValue("Разность");
                for (String minute : Configuration.get().MINUTES) {
                    row.createCell(columnIndex++).setCellValue(minute);
                }
                row.createCell(columnIndex++).setCellValue("Итого");
            }
        }
        //noinspection UnusedAssignment
        row.createCell(columnIndex++).setCellValue("Нет информации");
    }

    private void writeRow(Row row, Map.Entry<List<String>, Collection<Match>> entry) {
        log.debug("Обработка: " + entry.getKey());
        log.debug("Матчей в категории: " + entry.getValue().size());
        int columnIndex = 0;
        row.createCell(columnIndex++).setCellValue(entry.getKey().get(1));
        row.createCell(columnIndex++).setCellValue(entry.getKey().get(2));
        row.createCell(columnIndex++).setCellValue(entry.getKey().get(3));
        if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.TOTAL) {
            for (String total : Configuration.get().TOTALS) {
                log.debug("Обработка тотала: " + total);
                row.createCell(columnIndex++).setCellValue(total);
                for (String minute : Configuration.get().MINUTES) {
                    row.createCell(columnIndex++).setCellValue(calcTotalByMinute(total, minute, entry.getValue()));
                }
                row.createCell(columnIndex++).setCellValue(calcSummary(entry.getValue(), total));
            }
        } else if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.SUBTRACT) {
            for (String subtract : Configuration.get().SUBTRACTS) {
                log.debug("Обработка разности: " + subtract);
                row.createCell(columnIndex++).setCellValue(subtract);
                for (String minute : Configuration.get().MINUTES) {
                    row.createCell(columnIndex++).setCellValue(calcSubtractByMinute(subtract, minute, entry.getValue()));
                }
                row.createCell(columnIndex++).setCellValue(calcSummary(entry.getValue(), subtract));
            }
        }
        List<String> list = noInfoUrls.get(entry.getKey());
        //noinspection UnusedAssignment
        row.createCell(columnIndex++).setCellValue(list != null ? list.size() : 0);
    }

    private double calcSummary(Collection<Match> matches, String totalOrSubtract) {
        List<Match> filtered = new ArrayList<>();
        for (Match match : matches) {
            Pair<Integer, Integer> score;
            if (Configuration.get().MATCH_SUMMARY_TYPE == Configuration.MatchSummaryType.BY_HT) {
                score = match.getScoreHt();
            } else if (Configuration.get().MATCH_SUMMARY_TYPE == Configuration.MatchSummaryType.BY_FT) {
                score = match.getScoreFt();
            } else {
                throw new IllegalArgumentException("Неверное значение MATCH_SUMMARY_TYPE: " + Configuration.get().MATCH_SUMMARY_TYPE);
            }
            int result;
            if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.TOTAL) {
                result = score.getLeft() + score.getRight();
            } else if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.SUBTRACT) {
                result = Math.abs(score.getLeft() - score.getRight());
            } else {
                throw new IllegalArgumentException("Неверное значение GOAL_FILTER_TYPE: " + Configuration.get().GOAL_FILTER_TYPE);
            }
            if (Utils.checkInRange(result + "", totalOrSubtract, false)) {
                filtered.add(match);
            } else {
                log.debug("Не прошёл проверку для ИТОГО: " + match.getUrl());
            }
        }
        log.debug("Итого матчей: " + filtered.size());
        {
            List<Match>
                    count45 = new ArrayList<>(),
                    count90 = new ArrayList<>(),
                    count45plus = new ArrayList<>(),
                    count90plus = new ArrayList<>();
            for (Match filteredMatch : filtered) {
                for (Match.Goal goal : filteredMatch.getGoals()) {
                    if (goal.getMinute().startsWith("45+")) {
                        count45plus.add(filteredMatch);
                    } else if (goal.getMinute().startsWith("90+")) {
                        count90plus.add(filteredMatch);
                    } else if (goal.getMinute().equals("45")) {
                        count45.add(filteredMatch);
                    } else if (goal.getMinute().equals("90")) {
                        count90.add(filteredMatch);
                    }
                }
            }
            if (Configuration.get().MATCH_SUMMARY_TYPE == Configuration.MatchSummaryType.BY_HT) {
                log.debug("Из них матчей с голами на 45 минуте: " + count45.size());
                log.debug("Из них матчей с голами на 45+ минуте: " + count45plus.size());
                log.debug(Configuration.get().MIN_45.toString());
                filtered.removeAll(Configuration.get().MIN_45.calc(count45));
                filtered.removeAll(count45plus);
            } else if (Configuration.get().MATCH_SUMMARY_TYPE == Configuration.MatchSummaryType.BY_FT) {
                log.debug("Из них матчей с голами на 90 минуте: " + count90.size());
                log.debug("Из них матчей с голами на 90+ минуте: " + count90plus.size());
                log.debug(Configuration.get().MIN_90.toString());
                filtered.removeAll(Configuration.get().MIN_90.calc(count90));
                filtered.removeAll(count90plus);
            }

            log.debug("После вычитания матчей: " + filtered.size());
        }
        return filtered.size();
    }

    private double calcTotalByMinute(String total, String minute, Collection<Match> matches) {
        int c = 0;
        for (Match match : matches) {
            for (Match.Goal goal : match.getGoals()) {
                if (FilterUtils.checkGoalByMinute(goal, minute) && FilterUtils.checkByTotal(goal, total)) {
                    c++;
                    break;
                }
            }
        }
        return c;
    }

    private double calcSubtractByMinute(String subtract, String minute, Collection<Match> matches) {
        int c = 0;
        for (Match match : matches) {
            for (Match.Goal goal : match.getGoals()) {
                if (FilterUtils.checkGoalByMinute(goal, minute) && FilterUtils.checkBySubtract(goal, subtract)) {
                    c++;
                    break;
                }
            }
        }
        return c;
    }
}
