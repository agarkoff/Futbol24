package ru.misterparser.futbol24;

import org.apache.commons.lang3.StringUtils;

import javax.swing.table.DefaultTableModel;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Created with IntelliJ IDEA.
 * User: Stas
 * Date: 17.07.16
 * Time: 17:23
 */
public class MatchTableModel extends DefaultTableModel {

    static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private List<Match> items = new CopyOnWriteArrayList<>();

    MatchTableModel() {
    }

    @Override
    synchronized public Class<?> getColumnClass(int columnIndex) {
        return String.class;
    }

    @Override
    synchronized public boolean isCellEditable(int row, int column) {
        return false;
    }

    @Override
    synchronized public int getRowCount() {
        return items != null ? items.size() : 0;
    }

    @Override
    synchronized public int getColumnCount() {
        return columnIdentifiers.size();
    }

    @Override
    synchronized public Object getValueAt(int rowIndex, int columnIndex) {
        Match match = items.get(rowIndex);
        String[] strings = getStrings(match);
        return columnIndex < strings.length ? strings[columnIndex] : "";
    }

    synchronized private String[] getStrings(Match match) {
        return new String[]{
                match.getDate().format(DATE_TIME_FORMATTER),
                StringUtils.join(match.getCategories(), " » "),
                match.getHomeTeam(),
                match.getGuestTeam(),
                match.getGoals().stream().map(this::formatGoal).collect(Collectors.joining(", ")),
                String.valueOf(match.getScoreHt()),
                String.valueOf(match.getScoreFt()),
                match.getUrl()};
    }

    private String formatGoal(Match.Goal goal) {
        return goal.getMinute() + " " + goal.getScoreBefore();
    }

    synchronized private String[] getHeaders() {
        return new String[]{"Дата", "Лига", "Хозяин", "Гость", "Голы", "HT", "FT", "URL"};
    }

    synchronized void setColumnIdentifiers() {
        super.setColumnIdentifiers(getHeaders());
        fireTableStructureChanged();
    }

    synchronized Match getMatch(Integer row) {
        return items.get(row);
    }

    synchronized void clear() {
        items.clear();
        fireTableDataChanged();
    }

    synchronized void addItem(Match match) {
        items.add(match);
        fireTableRowsInserted(items.size() - 1, items.size() - 1);
    }

    synchronized List<Match> getMatches() {
        return Collections.unmodifiableList(items);
    }
}
