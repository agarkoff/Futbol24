package ru.misterparser.futbol24;

import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import ru.misterparser.common.configuration.ConfigurationSetter;
import ru.misterparser.common.gui.GuiUtils;
import ru.misterparser.common.model.NamedElement;

import javax.swing.tree.TreeModel;
import java.io.Serializable;
import java.util.List;

/**
 * Created by IntelliJ IDEA.
 * User: Стас
 * Date: 02.09.12
 * Time: 12:34
 */
public class Configuration implements Serializable, ConfigurationSetter<Configuration> {

	private static final long serialVersionUID = 1;

	private static Configuration configuration = new Configuration();

    private Configuration() {
    }

    static Configuration get() {
        return configuration;
    }

    public void set(Configuration configuration) {
        Configuration.configuration = configuration;
    }

    GuiUtils.DirectoryHolder CURRENT_DIRECTORY;
    TreeModel CATEGORIES;
    List<String> MINUTES;
    List<String> TOTALS;
    List<String> SUBTRACTS;
    GoalFilterType GOAL_FILTER_TYPE;
    boolean SEARCH;
    MatchSummaryType MATCH_SUMMARY_TYPE;
    String NUMBER_SEASONS;
    SubtractType MIN_45;
    SubtractType MIN_90;

    static int NETWORK_TIMEOUT = 20000;

    @Override
    public String toString() {
        return new ReflectionToStringBuilder(this).toString();
    }

    enum GoalFilterType {
        TOTAL,
        SUBTRACT
    }

    enum MatchSummaryType implements NamedElement {
        BY_HT("только по итогам первого тайма"),
        BY_FT("по всему матчу");

        private String name;

        MatchSummaryType(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    enum SubtractType implements NamedElement {
        NONE("ничего", 0),
        HALF("половину", 0.5),
        ALL("все", 1.0);

        private String name;
        private double k;

        SubtractType(String name, double k) {
            this.name = name;
            this.k = k;
        }

        public String getName() {
            return name;
        }

        public List<Match> calc(List<Match> matches) {
            int m = (int) Math.ceil(k * matches.size());
            return matches.subList(0, m);
        }
    }
}
