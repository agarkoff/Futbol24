package ru.misterparser.futbol24;

import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.uiDesigner.core.Spacer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jdesktop.swingx.JXTable;
import org.jdesktop.swingx.prompt.PromptSupport;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import ru.misterparser.common.JSoupUtils;
import ru.misterparser.common.MavenProperties;
import ru.misterparser.common.Utils;
import ru.misterparser.common.YandexUtils;
import ru.misterparser.common.configuration.ApplyConfigurationActionListener;
import ru.misterparser.common.configuration.ConfigurationApplier;
import ru.misterparser.common.configuration.ConfigurationUtils;
import ru.misterparser.common.configuration.StateUpdater;
import ru.misterparser.common.flow.LogEventProcessor;
import ru.misterparser.common.flow.ThreadFinishStatus;
import ru.misterparser.common.gui.BrowseItemMouseListener;
import ru.misterparser.common.gui.ContextMenuEventListener;
import ru.misterparser.common.gui.GuiUtils;
import ru.misterparser.common.gui.radiobutton.RadioButtonGroup;
import ru.misterparser.common.gui.tree.TreeUtils;
import ru.misterparser.common.model.Category;
import ru.misterparser.common.sp.SpUtils;

import javax.swing.*;
import javax.swing.plaf.basic.BasicTableUI;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Created with IntelliJ IDEA.
 * User: Stas
 * Date: 04.02.14
 * Time: 23:31
 */
@Slf4j
public class MainFrame implements ConfigurationApplier {

    private static final String FRAME_TITLE = "Futbol24 Parser";
    private static final String LIST_DELIMITER = ",";

    private JFrame frame;
    private JPanel rootPanel;
    private JTabbedPane tabbedPane;
    private JLabel itemCountLabel;
    private JTextArea logTextArea;
    private JScrollPane logScrollPane;
    private JXTable matchTable;
    private JScrollPane itemScrollPane;
    private JButton uploadLogButton;
    private JTextField searchTextField;
    private JScrollPane categoriesScrollPane;
    private JTree categoriesTree;
    private JButton refreshCategoriesButton;
    private JTextField minutesTextField;
    private JButton loadMatchesButton;
    private JLabel noInfoLabel;
    private JButton minuteHelpButton;
    private JRadioButton totalsRadioButton;
    private JTextField totalsTextField;
    private JRadioButton subtractsRadioButton;
    private JTextField subtractsTextField;
    private JCheckBox searchCheckBox;
    private JPanel matchSummaryTypePanel;
    private JButton saveButton;
    private JTextField numberSeasonsTextField;
    private JPanel min90SubtractPanel;
    private JPanel min45SubtractPanel;
    private JButton clearCacheButton;

    private Thread parserThread;
    private boolean isStarted = false;
    private MatchTableModel matchTableModel = new MatchTableModel();
    private ErrorLogEventProcessor eventProcessor;
    private Map<List<String>, List<String>> noInfoUrls = new ConcurrentHashMap<>();
    private RadioButtonGroup<Configuration.MatchSummaryType> matchSummaryTypeRadioButtonGroup;
    private RadioButtonGroup<Configuration.SubtractType> min45SubtractTypeRadioButtonGroup;
    private RadioButtonGroup<Configuration.SubtractType> min90SubtractTypeRadioButtonGroup;

    private ActionListener loadMatchesButtonListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            applyConfiguration(false);
            if (isStarted) {
                parserThread.interrupt();
            } else {
                try {
                    eventProcessor.reset();
                    matchTableModel.clear();
                    noInfoUrls.clear();
                    updateCounter();
                    MatchLoader matchLoader = new MatchLoader(MainFrame.this, categoriesTree, eventProcessor);
                    parserThread = new Thread(matchLoader, "MatchLoader");
                    parserThread.start();
                    loadMatchesButton.setText("Стоп");
                    isStarted = true;
                } catch (Exception ex) {
                    log.debug("Exception", ex);
                    JOptionPane.showMessageDialog(frame, "Ошибка запуска потока\n" + ExceptionUtils.getStackTrace(ex), FRAME_TITLE, JOptionPane.ERROR_MESSAGE);
                    resetButtonState();
                }
            }
        }
    };

    private ActionListener uploadLogButtonListener = actionEvent -> new Thread(this::uploadLog).start();

    private ActionListener refreshCategoriesButtonListener = e -> {
        Configuration.get().CATEGORIES = null;
        initTree();
    };

    private ActionListener minuteHelpButtonListener = e -> JOptionPane.showMessageDialog(frame, "Укажите через запятую минуты так, как они указаны на сайте:\n21,38,90+\n45+ или 90+ обозначает все дополнительные минуты\nМожно указать диапазон минут:\n65-78,44\nАналогично задаются списки диапазонов тоталов и разностей", FRAME_TITLE, JOptionPane.INFORMATION_MESSAGE);

    private ActionListener saveButtonListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            try {
                applyConfiguration(false);
                String filename = GuiUtils.getFilename(frame, "Сохранить", FileDialog.SAVE, "xlsx", Configuration.get().CURRENT_DIRECTORY);
                if (filename != null) {
                    Exporter exporter = new Exporter(filename, matchTableModel.getMatches(), noInfoUrls);
                    exporter.save();
                    JOptionPane.showMessageDialog(frame, "Файл сохранен под именем " + filename);
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(frame, "Ошибка сохранения\n" + ExceptionUtils.getStackTrace(e), FRAME_TITLE, JOptionPane.ERROR_MESSAGE);
            }
        }
    };

    private ActionListener clearCacheButtonListener = new ActionListener() {
        @Override
        public void actionPerformed(ActionEvent actionEvent) {
            try {
                List<String> filenames = new ArrayList<>();
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) categoriesTree.getModel().getRoot();
                Enumeration<TreeNode> enumeration = root.depthFirstEnumeration();
                while (enumeration.hasMoreElements()) {
                    DefaultMutableTreeNode treeNode = (DefaultMutableTreeNode) enumeration.nextElement();
                    if (treeNode.isLeaf()) {
                        Category category = (Category) treeNode.getUserObject();
                        String cacheDirectory = ConfigurationUtils.getCurrentDirectory() + "cache/";
                        if (Utils.isExistsCacheKey(category.getUrl(), null, cacheDirectory)) {
                            String key = Utils.getCacheKeyByUrl(category.getUrl(), null);
                            String filename = cacheDirectory + key;
                            filenames.add(filename);
                        }
                    }
                }
                log.debug("Список файлов для удаления из кеша: " + filenames.size());
                for (String filename : filenames) {
                    try {
                        FileUtils.forceDelete(new File(filename));
                    } catch (Exception e) {
                        log.debug("Exception", e);
                    }
                }
            } catch (Exception e) {
                log.debug("Exception", e);
            }
        }
    };

    private void uploadLog() {
        String appName = FRAME_TITLE.replace("Parser", "");
        appName = StringUtils.trim(appName);
        YandexUtils.uploadLog(appName, logTextArea.getText());
    }

    private void resetButtonState() {
        isStarted = false;
        loadMatchesButton.setText("Загрузить матчи");
    }

    private void updateCounter() {
        SwingUtilities.invokeLater(() -> {
            String text = "Найдено: " + matchTableModel.getMatches().size();
            text += " (Показано: " + matchTable.getRowCount() + ")";
            itemCountLabel.setText(text);
            noInfoLabel.setText("Нет информации: " + noInfoUrls.values().stream().map(List::size).mapToInt(Integer::intValue).sum());
        });
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        createUIComponents();
        rootPanel.setLayout(new GridLayoutManager(2, 5, new Insets(5, 5, 5, 5), -1, -1));
        tabbedPane = new JTabbedPane();
        rootPanel.add(tabbedPane, new GridConstraints(0, 0, 1, 5, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, new Dimension(200, 200), null, 0, false));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(5, 2, new Insets(5, 5, 5, 5), -1, -1));
        tabbedPane.addTab("Главная", panel1);
        itemScrollPane = new JScrollPane();
        panel1.add(itemScrollPane, new GridConstraints(4, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        matchTable = new JXTable();
        matchTable.setFillsViewportHeight(false);
        matchTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        itemScrollPane.setViewportView(matchTable);
        final JPanel panel2 = new JPanel();
        panel2.setLayout(new GridLayoutManager(1, 3, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel2, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label1 = new JLabel();
        label1.setText("Минуты");
        panel2.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        minutesTextField = new JTextField();
        panel2.add(minutesTextField, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        minuteHelpButton = new JButton();
        minuteHelpButton.setIcon(new ImageIcon(getClass().getResource("/question.png")));
        minuteHelpButton.setText("");
        panel2.add(minuteHelpButton, new GridConstraints(0, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel3 = new JPanel();
        panel3.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel3, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        totalsRadioButton = new JRadioButton();
        totalsRadioButton.setSelected(true);
        totalsRadioButton.setText("тоталы");
        panel3.add(totalsRadioButton, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        totalsTextField = new JTextField();
        panel3.add(totalsTextField, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        subtractsRadioButton = new JRadioButton();
        subtractsRadioButton.setText("разности");
        panel3.add(subtractsRadioButton, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        subtractsTextField = new JTextField();
        panel3.add(subtractsTextField, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel4 = new JPanel();
        panel4.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel4, new GridConstraints(3, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        searchCheckBox = new JCheckBox();
        searchCheckBox.setText("");
        panel4.add(searchCheckBox, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        searchTextField = new JTextField();
        panel4.add(searchTextField, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        final JPanel panel5 = new JPanel();
        panel5.setLayout(new GridLayoutManager(2, 2, new Insets(0, 0, 0, 0), -1, -1));
        panel4.add(panel5, new GridConstraints(0, 0, 1, 2, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        label2.setText("Вычитаем из Итого матчи с голами на 45 минуте");
        panel5.add(label2, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        min45SubtractPanel = new JPanel();
        min45SubtractPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel5.add(min45SubtractPanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label3 = new JLabel();
        label3.setText("Вычитаем из Итого матчи с голами на 90 минуте");
        panel5.add(label3, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        min90SubtractPanel = new JPanel();
        min90SubtractPanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel5.add(min90SubtractPanel, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JPanel panel6 = new JPanel();
        panel6.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel6, new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label4 = new JLabel();
        label4.setText("Общие тотал/разность в файле");
        panel6.add(label4, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        matchSummaryTypePanel = new JPanel();
        matchSummaryTypePanel.setLayout(new GridLayoutManager(1, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel6.add(matchSummaryTypePanel, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JPanel panel7 = new JPanel();
        panel7.setLayout(new GridLayoutManager(5, 1, new Insets(0, 0, 0, 0), -1, -1));
        panel1.add(panel7, new GridConstraints(0, 0, 5, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        categoriesScrollPane = new JScrollPane();
        panel7.add(categoriesScrollPane, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, new Dimension(200, -1), new Dimension(200, -1), null, 0, false));
        categoriesTree = new JTree();
        categoriesTree.setShowsRootHandles(false);
        categoriesScrollPane.setViewportView(categoriesTree);
        final JPanel panel8 = new JPanel();
        panel8.setLayout(new GridLayoutManager(1, 2, new Insets(0, 0, 0, 0), -1, -1));
        panel7.add(panel8, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, null, null, null, 0, false));
        final JLabel label5 = new JLabel();
        label5.setText("Авт. выд. № сезонов");
        panel8.add(label5, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, 1, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        numberSeasonsTextField = new JTextField();
        panel8.add(numberSeasonsTextField, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1), null, 0, false));
        refreshCategoriesButton = new JButton();
        refreshCategoriesButton.setText("Обновить соревнования");
        panel7.add(refreshCategoriesButton, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, 1, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        loadMatchesButton = new JButton();
        loadMatchesButton.setIcon(new ImageIcon(getClass().getResource("/arrow-right.png")));
        loadMatchesButton.setText("Загрузить матчи");
        panel7.add(loadMatchesButton, new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, 1, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        clearCacheButton = new JButton();
        clearCacheButton.setText("Очистить кеш списков матчей");
        panel7.add(clearCacheButton, new GridConstraints(4, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JPanel panel9 = new JPanel();
        panel9.setLayout(new GridLayoutManager(1, 1, new Insets(5, 5, 5, 5), -1, -1));
        tabbedPane.addTab("Лог", panel9);
        logScrollPane = new JScrollPane();
        panel9.add(logScrollPane, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_BOTH, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_WANT_GROW, null, null, null, 0, false));
        logTextArea = new JTextArea();
        logScrollPane.setViewportView(logTextArea);
        itemCountLabel = new JLabel();
        itemCountLabel.setText("Найдено: 0");
        rootPanel.add(itemCountLabel, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        uploadLogButton = new JButton();
        uploadLogButton.setText("Отправить лог");
        rootPanel.add(uploadLogButton, new GridConstraints(1, 4, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, 1, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final Spacer spacer1 = new Spacer();
        rootPanel.add(spacer1, new GridConstraints(1, 2, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_WANT_GROW, 1, null, null, null, 0, false));
        noInfoLabel = new JLabel();
        noInfoLabel.setText("Нет информации: 0");
        rootPanel.add(noInfoLabel, new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_NONE, GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        saveButton = new JButton();
        saveButton.setIcon(new ImageIcon(getClass().getResource("/save.png")));
        saveButton.setText("Сохранить");
        rootPanel.add(saveButton, new GridConstraints(1, 3, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL, GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        ButtonGroup buttonGroup;
        buttonGroup = new ButtonGroup();
        buttonGroup.add(subtractsRadioButton);
        buttonGroup.add(totalsRadioButton);
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return rootPanel;
    }

    synchronized void updateNoInfoUrls(List<String> categories, String url) {
        List<String> list = noInfoUrls.computeIfAbsent(categories, k -> new ArrayList<>());
        list.add(url);
        updateCounter();
    }

    private class ErrorLogEventProcessor extends LogEventProcessor<Match> {

        ErrorLogEventProcessor(String tabTitle, String messageTitle, JTabbedPane tabbedPane) {
            super(tabTitle, messageTitle, tabbedPane);
        }

        @Override
        public void find(Match match) {
            log.debug("Найден матч: " + match);
            matchTableModel.addItem(match);
            updateCounter();
        }

        @Override
        public void finish(ThreadFinishStatus threadFinishStatus, Throwable throwable) {
            log.debug("Скачано матчей: " + matchTableModel.getMatches().size());
            resetButtonState();
            super.finish(threadFinishStatus, throwable);
        }
    }

    private MainFrame(String currentDirectory, @SuppressWarnings("UnusedParameters") String[] args) {
        $$$setupUI$$$();
        ConfigurationUtils.setCurrentDirectory(currentDirectory);
    }

    public static void main(String[] args) {
        System.setProperty("java.net.preferIPv4Stack", "true");
        System.setProperty("socksProxyVersion", "4");
        String currentDirectoryTemp = new File("").getAbsolutePath();
        if (args.length > 0) {
            currentDirectoryTemp = args[0];
        }
        currentDirectoryTemp += System.getProperty("file.separator");
        final String currentDirectory = currentDirectoryTemp;
        final MainFrame mainFrame = new MainFrame(currentDirectory, args);
        mainFrame.start();
    }

    private void start() {
        try {
            SwingUtilities.invokeLater(this::createAndShowGUI);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Ошибка создания окна\n" + ExceptionUtils.getStackTrace(e), FRAME_TITLE, JOptionPane.ERROR_MESSAGE);
        }
    }

    private void createAndShowGUI() {
        eventProcessor = new ErrorLogEventProcessor("Ошибки", FRAME_TITLE, tabbedPane);

        Toolkit.getDefaultToolkit().addAWTEventListener(new ContextMenuEventListener(), AWTEvent.MOUSE_EVENT_MASK | AWTEvent.KEY_EVENT_MASK);

        MavenProperties mavenProperties = MavenProperties.load();
        frame = new JFrame(FRAME_TITLE + " " + mavenProperties.getProjectVersion());
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setVisible(true);
        frame.setContentPane(rootPanel);
        Dimension minimumSize = new Dimension(850, 700);
        frame.setMinimumSize(minimumSize);
        frame.setSize(minimumSize);

        GuiUtils.updateUIOnPanel(rootPanel);
        GuiUtils.adjustmentScrollPane(categoriesScrollPane);
        GuiUtils.adjustmentScrollPane(itemScrollPane);
        GuiUtils.adjustmentScrollPaneWithTextArea(logScrollPane, logTextArea);

        GuiUtils.frameDisplayCenter(frame);

        uploadLogButton.addActionListener(uploadLogButtonListener);
        refreshCategoriesButton.addActionListener(refreshCategoriesButtonListener);
        loadMatchesButton.addActionListener(loadMatchesButtonListener);
        minuteHelpButton.addActionListener(minuteHelpButtonListener);
        saveButton.addActionListener(saveButtonListener);
        clearCacheButton.addActionListener(clearCacheButtonListener);

        matchTableModel.setColumnIdentifiers();
        matchTable.setModel(matchTableModel);
        matchTable.setUI(new BasicTableUI());
        matchTable.addMouseListener(new BrowseItemMouseListener(matchTable));
        categoriesTree.setUI(new BasicTreeUI());
        matchTable.setRowHeight(16);

        LogbackTextAreaAppender.setTextArea(logTextArea);
        LogbackTextAreaAppender.setMaxLength(500000);
        GuiUtils.setupSearchByKeyboard(logTextArea);
        GuiUtils.setupFrameIconImage(frame, getClass());

        KeyAdapter keyAdapter = new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                applyConfiguration(false);
                refilter();
            }
        };
        searchTextField.addKeyListener(keyAdapter);
        minutesTextField.addKeyListener(keyAdapter);
        totalsTextField.addKeyListener(keyAdapter);
        subtractsTextField.addKeyListener(keyAdapter);
        numberSeasonsTextField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyReleased(KeyEvent e) {
                applyConfiguration(false);
            }
        });

        ApplyConfigurationActionListener l = new ApplyConfigurationActionListener(this, false, () -> {
            goalFilterTypeStateUpdater();
            refilter();
        });
        totalsRadioButton.addActionListener(l);
        subtractsRadioButton.addActionListener(l);

        searchCheckBox.addActionListener(new ApplyConfigurationActionListener(this, false, searchStateUpdater));

        matchSummaryTypeRadioButtonGroup = new RadioButtonGroup<>(matchSummaryTypePanel, Configuration.MatchSummaryType.class, new ApplyConfigurationActionListener(this, false, matchSummaryStateUpdater));
        matchSummaryTypeRadioButtonGroup.recreateRadiobuttons(RadioButtonGroup.Direction.HORIZONTAL);

        min45SubtractTypeRadioButtonGroup = new RadioButtonGroup<>(min45SubtractPanel, Configuration.SubtractType.class, new ApplyConfigurationActionListener(this));
        min45SubtractTypeRadioButtonGroup.recreateRadiobuttons(RadioButtonGroup.Direction.HORIZONTAL);

        min90SubtractTypeRadioButtonGroup = new RadioButtonGroup<>(min90SubtractPanel, Configuration.SubtractType.class, new ApplyConfigurationActionListener(this));
        min90SubtractTypeRadioButtonGroup.recreateRadiobuttons(RadioButtonGroup.Direction.HORIZONTAL);

        PromptSupport.setPrompt("Простой текстовый фильтр по матчам...", searchTextField);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> applyConfiguration(false)));

        ConfigurationUtils.restoreConfiguration(Configuration.get());
        populateMainFrame();

        initTree();

        TableRowSorter<MatchTableModel> sorter = new TableRowSorter<>(matchTableModel);
        matchTable.setRowSorter(sorter);
        List<RowSorter.SortKey> sortKeys = new ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(0, SortOrder.DESCENDING));
        sorter.setSortKeys(sortKeys);
        sorter.setRowFilter(rowFilter);

        categoriesTree.addTreeSelectionListener(tse -> {
            if (StringUtils.isNotBlank(Configuration.get().NUMBER_SEASONS)) {
                try {
                    TreePath treePath = tse.getPath();
                    DefaultMutableTreeNode lastPathComponent = (DefaultMutableTreeNode) treePath.getLastPathComponent();
                    Enumeration<TreeNode> children = lastPathComponent.children();
                    List<Integer> numberSeasons = SpUtils.getSizeLine(Configuration.get().NUMBER_SEASONS, 1).stream().mapToInt(Integer::parseInt).boxed().collect(Collectors.toList());
                    int i = 1;
                    while (children.hasMoreElements()) {
                        TreeNode child = children.nextElement();
                        if (numberSeasons.contains(i) && !child.children().hasMoreElements()) {
                            SwingUtilities.invokeLater(() -> {
                                TreePath childTreePath = treePath.pathByAddingChild(child);
                                categoriesTree.removeSelectionPath(treePath);
                                categoriesTree.addSelectionPath(childTreePath);
                            });
                        }
                        i++;
                    }

                } catch (Exception e) {
                    log.debug("Exception", e);
                }
            }
        });

        // эта строка всегда должна быть в конце, чтобы не было вызова applyConfiguration из populateMainFrame
    }

    private StateUpdater matchSummaryStateUpdater = new StateUpdater() {
        @Override
        public void update() {
            min45SubtractTypeRadioButtonGroup.setEnabled(Configuration.get().MATCH_SUMMARY_TYPE == Configuration.MatchSummaryType.BY_HT);
            min90SubtractTypeRadioButtonGroup.setEnabled(Configuration.get().MATCH_SUMMARY_TYPE == Configuration.MatchSummaryType.BY_FT);
        }
    };

    private void goalFilterTypeStateUpdater() {
        totalsTextField.setEnabled(Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.TOTAL);
        subtractsTextField.setEnabled(Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.SUBTRACT);
    }

    private StateUpdater searchStateUpdater = new StateUpdater() {
        @Override
        public void update() {
            searchTextField.setEnabled(Configuration.get().SEARCH);
        }
    };

    private void refilter() {
        matchTableModel.fireTableDataChanged();
        updateCounter();
    }

    private RowFilter<MatchTableModel, Integer> rowFilter = new RowFilter<>() {
        @Override
        public boolean include(Entry<? extends MatchTableModel, ? extends Integer> entry) {
            MatchTableModel tableModel = entry.getModel();
            Match match = tableModel.getMatch(entry.getIdentifier());
            {
                if (!FilterUtils.checkMinuteFilter(match)) {
                    return false;
                }
            }
            {
                if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.TOTAL) {
                    boolean filter = Configuration.get().TOTALS.isEmpty();
                    for (Match.Goal goal : match.getGoals()) {
                        if (FilterUtils.checkGoalByMinutes(goal)) {
                            for (String totalRange : Configuration.get().TOTALS) {
                                if (FilterUtils.checkByTotal(goal, totalRange)) {
                                    filter = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!filter) {
                        return false;
                    }
                } else if (Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.SUBTRACT) {
                    boolean filter = Configuration.get().SUBTRACTS.isEmpty();
                    for (Match.Goal goal : match.getGoals()) {
                        if (FilterUtils.checkGoalByMinutes(goal)) {
                            for (String subtractRange : Configuration.get().SUBTRACTS) {
                                if (FilterUtils.checkBySubtract(goal, subtractRange)) {
                                    filter = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!filter) {
                        return false;
                    }
                }
            }
            {
                boolean searchFilter = !Configuration.get().SEARCH;
                {
                    int ei = entry.getIdentifier();
                    for (int i = 0; i < tableModel.getColumnCount(); i++) {
                        String s = String.valueOf(tableModel.getValueAt(ei, i));
                        if (StringUtils.isBlank(searchTextField.getText()) || StringUtils.containsIgnoreCase(s, searchTextField.getText())) {
                            searchFilter = true;
                            break;
                        }
                    }
                }
                //noinspection RedundantIfStatement
                if (!searchFilter) {
                    return false;
                }
            }
            return true;
        }
    };

    private void initTree() {
        TreeUtils.initTree(categoriesTree, Configuration.get().CATEGORIES, treeLoader, this/*, () -> categoriesTree.setSelectionInterval(0, categoriesTree.getRowCount() - 1)*/);
    }

    private TreeUtils.TreeLoader treeLoader = () -> {
        DefaultMutableTreeNode rootTreeNode = new DefaultMutableTreeNode();
        DefaultTreeModel defaultTreeModel = new DefaultTreeModel(rootTreeNode);
        try {
            LeagueLoader leagueLoader = new LeagueLoader();
            Document document = leagueLoader.getRootNode("https://www.futbol24.com/", false);
            ThreadPoolExecutor executorService = (ThreadPoolExecutor) Executors.newFixedThreadPool(64);
            {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Category("Интернациональные", null));
                rootTreeNode.add(node);
                for (Element a : document.select("div.international ul.countries > li > a")) {
                    submitTask(executorService, node, a);
                }
            }
            {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Category("Национальные", null));
                rootTreeNode.add(node);
                for (Element a : document.select("div.national ul.countries > li > a")) {
                    submitTask(executorService, node, a);
                }
            }
            log.debug("Задач в очереди: " + executorService.getQueue().size());
            executorService.shutdown();
            executorService.awaitTermination(1000, TimeUnit.HOURS);
        } catch (Exception e) {
            log.debug("Exception", e);
        }
        return defaultTreeModel;
    };

    private void submitTask(ThreadPoolExecutor executorService, DefaultMutableTreeNode node, Element a) {
        DefaultMutableTreeNode child = createNode(node, a);
        executorService.execute(() -> {
            try {
                addLeague(child, a.parent().attr("data-id"));
            } catch (Exception e) {
                log.debug("Exception", e);
            }
        });
    }

    private synchronized DefaultMutableTreeNode createNode(DefaultMutableTreeNode node, Element a) {
        String name = JSoupUtils.getText(a);
        String url = Utils.normalizeUrl(a.attr("href"), "https://www.futbol24.com/");
        Category category = new Category(name, url);
        DefaultMutableTreeNode newChild = new DefaultMutableTreeNode(category);
        node.add(newChild);
        return newChild;
    }

    private void addLeague(DefaultMutableTreeNode treeNode, String dataId) throws InterruptedException {
        Document document = new LeagueLoader().getRootNode("https://www.futbol24.com/ml/subLeagues/?CountryId=" + dataId, true);
        List<Element> as = document.select("ul > li > a");
        for (Element a : as) {
            DefaultMutableTreeNode node = createNode(treeNode, a);
            String leagueUrl = ((Category) node.getUserObject()).getUrl();
            Document leagueDoc = new LeagueLoader().getRootNode(leagueUrl, false);
            List<Element> options = leagueDoc.select("div.desc select.gray2.onchangeurl option");
            for (Element option : options) {
                String name = JSoupUtils.getText(option);
                String url = Utils.normalizeUrl(option.attr("value") + "results/", "https://www.futbol24.com/");
                Category category = new Category(name, url);
                DefaultMutableTreeNode newChild = new DefaultMutableTreeNode(category);
                node.add(newChild);
            }
        }
    }

    public void applyConfiguration(boolean verbose) {
        try {
            Configuration.get().CATEGORIES = categoriesTree.getModel();
            Configuration.get().MINUTES = Arrays.stream(minutesTextField.getText().split(LIST_DELIMITER)).map(Utils::squeezeText).filter(StringUtils::isNotBlank).collect(Collectors.toList());
            Configuration.get().TOTALS = Arrays.stream(totalsTextField.getText().split(LIST_DELIMITER)).map(Utils::squeezeText).filter(StringUtils::isNotBlank).collect(Collectors.toList());
            Configuration.get().SUBTRACTS = Arrays.stream(subtractsTextField.getText().split(LIST_DELIMITER)).map(Utils::squeezeText).filter(StringUtils::isNotBlank).collect(Collectors.toList());
            Configuration.get().GOAL_FILTER_TYPE = totalsRadioButton.isSelected() ? Configuration.GoalFilterType.TOTAL :
                    subtractsRadioButton.isSelected() ? Configuration.GoalFilterType.SUBTRACT : null;
            Configuration.get().SEARCH = searchCheckBox.isSelected();
            Configuration.get().MATCH_SUMMARY_TYPE = matchSummaryTypeRadioButtonGroup.getSelected();
            Configuration.get().NUMBER_SEASONS = numberSeasonsTextField.getText();
            Configuration.get().MIN_45 = min45SubtractTypeRadioButtonGroup.getSelected();
            Configuration.get().MIN_90 = min90SubtractTypeRadioButtonGroup.getSelected();
            log.debug("Настройки установлены");
            ConfigurationUtils.saveConfiguration(Configuration.get());
            if (verbose) {
                JOptionPane.showMessageDialog(frame, "Настройки установлены", FRAME_TITLE, JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            log.debug("Exception", e);
            if (verbose) {
                JOptionPane.showMessageDialog(frame, "Ошибка установки параметров\n" + ExceptionUtils.getStackTrace(e), FRAME_TITLE, JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void populateMainFrame() {
        {
            if (Configuration.get().CURRENT_DIRECTORY == null) {
                Configuration.get().CURRENT_DIRECTORY = new GuiUtils.DirectoryHolder();
            }
        }
        {
            if (Configuration.get().MINUTES == null) {
                Configuration.get().MINUTES = new ArrayList<>();
            }
            minutesTextField.setText(String.join(LIST_DELIMITER, Configuration.get().MINUTES));
        }
        {
            if (Configuration.get().TOTALS == null) {
                Configuration.get().TOTALS = new ArrayList<>();
            }
            totalsTextField.setText(String.join(LIST_DELIMITER, Configuration.get().TOTALS));
        }
        {
            if (Configuration.get().SUBTRACTS == null) {
                Configuration.get().SUBTRACTS = new ArrayList<>();
            }
            subtractsTextField.setText(String.join(LIST_DELIMITER, Configuration.get().SUBTRACTS));
        }
        {
            if (Configuration.get().GOAL_FILTER_TYPE == null) {
                Configuration.get().GOAL_FILTER_TYPE = Configuration.GoalFilterType.TOTAL;
            }
            totalsRadioButton.setSelected(Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.TOTAL);
            subtractsRadioButton.setSelected(Configuration.get().GOAL_FILTER_TYPE == Configuration.GoalFilterType.SUBTRACT);
            goalFilterTypeStateUpdater();
        }
        {
            searchCheckBox.setSelected(Configuration.get().SEARCH);
            searchStateUpdater.update();
        }
        matchSummaryTypeRadioButtonGroup.setSelected(Configuration.get().MATCH_SUMMARY_TYPE);
        numberSeasonsTextField.setText(Configuration.get().NUMBER_SEASONS);
        min45SubtractTypeRadioButtonGroup.setSelected(Configuration.get().MIN_45);
        min90SubtractTypeRadioButtonGroup.setSelected(Configuration.get().MIN_90);
        matchSummaryStateUpdater.update();
    }

    private void createUIComponents() {
        GuiUtils.initLAFJdk11();
        rootPanel = new JPanel();
    }
}
