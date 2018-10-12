package ru.misterparser.futbol24;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import ru.misterparser.common.JSoupUtils;
import ru.misterparser.common.Utils;
import ru.misterparser.common.configuration.ConfigurationUtils;
import ru.misterparser.common.flow.EventProcessor;
import ru.misterparser.common.flow.ThreadFinishStatus;
import ru.misterparser.common.gui.tree.TreeUtils;
import ru.misterparser.common.model.Category;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created with IntelliJ IDEA.
 * User: Stas
 * Date: 04.02.14
 * Time: 23:39
 */
@Slf4j
public class MatchLoader extends Loader {

    private static final Pattern SCORE_PATTERN = Pattern.compile("([0-9]+)\\s*-\\s*([0-9]+)");

    private MainFrame mainFrame;
    private JTree tree;
    private EventProcessor<Match> eventProcessor;

    private ThreadLocal<List<String>> currentCategories = new ThreadLocal<>();
    private ThreadLocal<Integer> categoriesOrder = new ThreadLocal<>();

    @SuppressWarnings("FieldCanBeLocal")
    private Properties parserProperties = new Properties();

    private MatchLoader() {
        try {
            parserProperties.load(new FileInputStream(ConfigurationUtils.getCurrentDirectory() + "parser.properties"));
        } catch (FileNotFoundException ignored) {
        } catch (Exception e) {
            log.debug("Exception", e);
        }
        isCache = Boolean.parseBoolean(parserProperties.getProperty("isCache", "false"));
        Utils.setTryCount(1);
        Utils.setTimeoutIOError(2000);
    }

    MatchLoader(MainFrame mainFrame, JTree tree, EventProcessor<Match> eventProcessor) {
        this();
        this.mainFrame = mainFrame;
        this.tree = tree;
        this.eventProcessor = eventProcessor;
    }

    @Override
    public void run() {
        ThreadFinishStatus threadFinishStatus = ThreadFinishStatus.ERROR;
        Throwable throwable = null;
        ThreadPoolExecutor executorService = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);
        try {
            log.debug("Конфигурация: " + Configuration.get().toString());
            List<Pair<String, List<String>>> categories = new ArrayList<>();
            {
                int[] treeRows = tree.getSelectionRows();
                //noinspection ConstantConditions
                Arrays.sort(treeRows);
                for (int treeRow : treeRows) {
                    TreePath treePath = tree.getPathForRow(treeRow);
                    DefaultMutableTreeNode lastPathComponent = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    categories.addAll(processTreeNode(lastPathComponent));
                }
                log.debug("К обработке разделов: " + categories.size());
            }
            {
                categoriesOrder.set(0);
                for (Pair<String, List<String>> category : categories) {
                    categoriesOrder.set(categoriesOrder.get() + 1);
                    int co = categoriesOrder.get();
                    executorService.execute(() -> {
                        try {
                            currentCategories.set(category.getRight());
                            categoriesOrder.set(co); // перекладываем из потока обработки сезона в поток обработки матча
                            log.debug("Категория: " + currentCategories.get());
                            processCategory(category.getLeft());
                        } catch (Exception e) {
                            log.debug("Exception", e);
                        }
                    });
                }
                log.debug("Задач в очереди: " + executorService.getQueue().size());
                executorService.shutdown();
                executorService.awaitTermination(1000, TimeUnit.HOURS);
            }
            threadFinishStatus = ThreadFinishStatus.COMPLETED;
        } catch (InterruptedException e) {
            log.debug("Остановка потока");
            executorService.shutdownNow();
            threadFinishStatus = ThreadFinishStatus.INTERRUPTED;
        } catch (Throwable t) {
            log.debug("Throwable", t);
            throwable = t;
        } finally {
            log.debug("Обработка завершена");
            eventProcessor.finish(threadFinishStatus, throwable);
        }
    }

    private List<Pair<String, List<String>>> processTreeNode(DefaultMutableTreeNode treeNode) {
        List<Pair<String, List<String>>> categories = new ArrayList<>();
        if (treeNode.getUserObject() instanceof Category) {
            if (treeNode.getChildCount() > 0) {
                for (int i = 0; i < treeNode.getChildCount(); i++) {
                    DefaultMutableTreeNode childrenNode = (DefaultMutableTreeNode) treeNode.getChildAt(i);
                    categories.addAll(processTreeNode(childrenNode));
                }
            } else {
                Category category = (Category) treeNode.getUserObject();
                categories.add(Pair.of(category.getUrl(), TreeUtils.getCategories(treeNode, true)));
            }
        }
        return categories;
    }

    private void processCategory(String url) throws InterruptedException {
        String page = fetch(url, null);
        Document document = Jsoup.parse(page);
        processPage(document);
        String nextPage = getNextPage(document, url);
        while (nextPage != null) {
            page = fetch(nextPage, null);
            document = Jsoup.parse(page);
            processPage(document);
            nextPage = getNextPage(document, url);
        }
    }

    private String getNextPage(Document document, String baseUrl) {
        Element element = document.selectFirst("div.next > a");
        if (element != null) {
            String href = element.attr("href");
            if (!StringUtils.containsIgnoreCase(href, "statLR-Page=0")) {
                return Utils.normalizeUrl(href, baseUrl);
            }
        }
        return null;
    }

    private void processPage(Document document) throws InterruptedException {
        ThreadPoolExecutor executorService = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);
        try {
            List<Element> as = document.select("table.stat2.stat > tbody > tr a.matchAction");
            for (Element a : as) {
                List<String> cats = currentCategories.get();
                int co = categoriesOrder.get();
                executorService.execute(() -> {
                    currentCategories.set(cats); // перекладываем из потока обработки сезона в поток обработки матча
                    categoriesOrder.set(co);
                    String itemUrl = Utils.normalizeUrl(a.attr("href"), "https://www.futbol24.com/");
                    String matchAction = JSoupUtils.getText(a);
                    if (StringUtils.containsIgnoreCase(matchAction, "P-P")) {
                        log.debug("Матч пересён: " + itemUrl);
                        mainFrame.updateNoInfoUrls(cats, itemUrl);
                    } else {
                        try {
                            processMatch(itemUrl);
                        } catch (InterruptedException e) {
                            log.debug("Остановка потока обработки категории...");
                        } catch (Throwable t) {
                            eventProcessor.log("Ошибка '" + Utils.squeezeText(t.getMessage()) + "' во время обработки ссылки " + itemUrl);
                            log.debug("Throwable", t);
                        }
                    }
                });
            }
            executorService.shutdown();
            executorService.awaitTermination(1000, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            throw e;
        } catch (Throwable t) {
            log.debug("Throwable", t);
        }
    }

    private void processMatch(String url) throws InterruptedException {
        //url = "https://www.futbol24.com/match/2017/02/27/international/International/Club-Friendly/2017/Hammarby/vs/Enskede/";
        String page = fetch(url, null);
        Document document = Jsoup.parse(page);
        LocalDateTime date;
        String homeTeam, guestTeam;
        {
            Element element = document.selectFirst("span.date.timezone");
            date = LocalDateTime.parse(JSoupUtils.getText(element), MatchTableModel.DATE_TIME_FORMATTER);
        }
        {
            Element element = document.selectFirst("thead > tr > td.home");
            homeTeam = JSoupUtils.getText(element);
        }
        {
            Element element = document.selectFirst("thead > tr > td.guest");
            guestTeam = JSoupUtils.getText(element);
        }
//        Pair<Integer, Integer> scoreHt = null, scoreFt = null;
//        {
//            for (Element tr : document.select("tbody > tr")) {
//
//                Element tdStatus = tr.selectFirst("td.status");
//                String t = JSoupUtils.getText(tdStatus);
//                if (StringUtils.equalsIgnoreCase(t, "HT")) {
//                    scoreHt = parseScore(tr);
//                } else if (StringUtils.equalsIgnoreCase(t, "FT")) {
//                    scoreFt = parseScore(tr);
//                }
//            }
//        }
        Pair<Integer, Integer> scoreHt = null, scoreFt = null;
        List<Match.Goal> goals = new ArrayList<>();
        {
            Elements trs = document.select("tbody > tr");
            if (trs.size() == 0) {
                log.debug("Нет информации о матче: " + url);
                mainFrame.updateNoInfoUrls(currentCategories.get(), url);
                return;
            }
            for (int i = 0; i < trs.size(); i++) {
                Element tr = trs.get(i);
                String trClass = tr.attr("class");
                Match.Goal.Type type = Match.Goal.Type.fromCss(trClass);
                if (type == Match.Goal.Type.RED_CARD ||
                        type == Match.Goal.Type.YELLOW_CARD ||
                        type == Match.Goal.Type.UNBEATEN_PENALTY) {
                    continue;
                }
                String minute = JSoupUtils.getText(tr.selectFirst("td.status"));
                {
                    Pattern pattern = Pattern.compile("([0-9]+)(\\+[0-9]+)?");
                    Matcher matcher = pattern.matcher(minute);
                    if (matcher.matches()) {
                        int m = Integer.parseInt(matcher.group(1));
                        if (m <= 45) {
                            scoreHt = parseScoreFromTableRow(tr);
                        } else if (m <= 90) {
                            scoreFt = parseScoreFromTableRow(tr);
                        }
                    }
                }
                String team;
                String player;
                if (StringUtils.containsIgnoreCase(trClass, "haction")) {
                    team = homeTeam;
                    player = JSoupUtils.getText(tr.selectFirst("td.home"));
                } else if (StringUtils.containsIgnoreCase(trClass, "gaction")) {
                    team = guestTeam;
                    player = JSoupUtils.getText(tr.selectFirst("td.guest"));
                } else {
                    continue;
                }
                Pair<Integer, Integer> scoreBefore = i > 0 ? parseScoreFromTableRow(trs.get(i - 1)) : Pair.of(0, 0);
                goals.add(new Match.Goal(type, minute, scoreBefore, team, player));
            }
            if (check45And90SeveralGoals(goals) || (goals.size() == 0 && !scoreNull(document))) {
                log.debug("Нет информации о матче: " + url);
                mainFrame.updateNoInfoUrls(currentCategories.get(), url);
                return;
            }
        }
        if (scoreHt == null) {
            scoreHt = Pair.of(0, 0);
        }
        if (scoreFt == null) {
            scoreFt = scoreHt;
        }
        Match match = new Match(date, homeTeam, guestTeam, currentCategories.get(), categoriesOrder.get(), scoreHt, scoreFt, url);
        goals.forEach(match::addGoal);
        checkForWait();
        eventProcessor.find(match);
    }

    private boolean check45And90SeveralGoals(List<Match.Goal> goals) {
        int c45 = 0, c90 = 0;
        for (Match.Goal goal : goals) {
            if (StringUtils.equalsIgnoreCase(goal.getMinute(), "45")) {
                c45++;
            } else if (StringUtils.equalsIgnoreCase(goal.getMinute(), "90")) {
                c90++;
            }
        }
        return c45 > 1 || c90 > 1;
    }

    private Pair<Integer, Integer> parseScoreFromTableRow(Element tr) {
        return parseScore(tr, "td.result");
    }

    private boolean scoreNull(Element document) {
        return parseScore(document, "td.result span.result1").equals(Pair.of(0, 0));
    }

    private Pair<Integer, Integer> parseScore(Element tr, String xpath) {
        Element td = tr.selectFirst(xpath);
        String t = JSoupUtils.getText(td);
        Matcher matcher = SCORE_PATTERN.matcher(t);
        if (matcher.matches()) {
            return Pair.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }
        throw new IllegalArgumentException("Ошибка формата результата матча: " + t);
    }
}
