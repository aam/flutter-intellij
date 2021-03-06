/*
 * Copyright 2018 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.logging;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.filters.UrlFilter;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.treeStructure.treetable.ListTreeTableModelOnColumns;
import com.intellij.ui.treeStructure.treetable.TreeTable;
import com.intellij.ui.treeStructure.treetable.TreeTableTree;
import com.intellij.util.Alarm;
import com.intellij.util.EventDispatcher;
import com.intellij.util.ui.ColumnInfo;
import com.jetbrains.lang.dart.ide.runner.DartConsoleFilter;
import io.flutter.console.FlutterConsoleFilter;
import io.flutter.run.daemon.FlutterApp;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN;
import static io.flutter.logging.FlutterLogConstants.LogColumns.*;

public class FlutterLogTree extends TreeTable {

  private static final SimpleDateFormat TIMESTAMP_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS");
  private static final Logger LOG = Logger.getInstance(FlutterLogTree.class);
  @NotNull
  private static final Map<FlutterLog.Level, String> LOG_LEVEL_FILTER;

  static {
    LOG_LEVEL_FILTER = new HashMap<>();
    LOG_LEVEL_FILTER.put(FlutterLog.Level.NONE, "NONE|FINEST|FINER|FINE|CONFIG|INFO|WARNING|SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.FINEST, "FINEST|FINER|FINE|CONFIG|INFO|WARNING|SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.FINER, "FINER|FINE|CONFIG|INFO|WARNING|SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.FINE, "FINE|CONFIG|INFO|WARNING|SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.CONFIG, "CONFIG|INFO|WARNING|SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.INFO, "INFO|WARNING|SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.WARNING, "WARNING|SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.SEVERE, "SEVERE|SHOUT");
    LOG_LEVEL_FILTER.put(FlutterLog.Level.SHOUT, "SHOUT");
  }

  private static class ColumnModel {

    private abstract class EntryCellRenderer extends ColoredTableCellRenderer {

      private final Border RELOAD_LINE =
        new CompoundBorder(BorderFactory.createEmptyBorder(0, -1, -1, -1), BorderFactory.createDashedBorder(
          JBColor.ORANGE, 5, 5));
      private final Border RESTART_LINE =
        new CompoundBorder(BorderFactory.createEmptyBorder(0, -1, -1, -1), BorderFactory.createDashedBorder(JBColor.GREEN, 5, 5));

      @Override
      protected final void customizeCellRenderer(JTable table,
                                                 @Nullable Object value,
                                                 boolean selected,
                                                 boolean hasFocus,
                                                 int row,
                                                 int column) {
        if (value instanceof FlutterLogEntry) {
          final FlutterLogEntry entry = (FlutterLogEntry)value;
          render(entry);
          if (row > 0) {
            if (entry.getKind() == FlutterLogEntry.Kind.RELOAD) {
              setBorder(RELOAD_LINE);
            }
            else if (entry.getKind() == FlutterLogEntry.Kind.RESTART) {
              setBorder(RESTART_LINE);
            }
          }
        }
      }

      @Override
      public void acquireState(JTable table, boolean isSelected, boolean hasFocus, int row, int column) {
        // Prevent cell borders on selected cells.
        super.acquireState(table, isSelected, false, row, column);
      }

      void appendStyled(FlutterLogEntry entry, String text) {
        final SimpleTextAttributes style = entryModel.style(entry, STYLE_PLAIN);
        if (style.getBgColor() != null) {
          setBackground(style.getBgColor());
        }
        append(text, style);
      }


      abstract void render(FlutterLogEntry entry);
    }

    class Column extends ColumnInfo<DefaultMutableTreeNode, FlutterLogEntry> {
      boolean show = true;
      @NotNull
      private final TableCellRenderer renderer;

      Column(@NotNull String name, @NotNull TableCellRenderer renderer) {
        super(name);
        this.renderer = renderer;
      }

      boolean isShowing() {
        return show;
      }

      @Nullable
      @Override
      public FlutterLogEntry valueOf(DefaultMutableTreeNode node) {
        if (node instanceof FlutterEventNode) {
          return ((FlutterEventNode)node).entry;
        }
        return null;
      }

      @Override
      public TableCellRenderer getCustomizedRenderer(DefaultMutableTreeNode o, TableCellRenderer renderer) {
        return this.renderer;
      }
    }

    private class TimeCellRenderer extends EntryCellRenderer {
      @Override
      void render(FlutterLogEntry entry) {
        appendStyled(entry, TIMESTAMP_FORMAT.format(entry.getTimestamp()));
      }
    }

    private class SequenceCellRenderer extends EntryCellRenderer {
      @Override
      void render(FlutterLogEntry entry) {
        appendStyled(entry, Integer.toString(entry.getSequenceNumber()));
      }
    }

    private class LevelCellRenderer extends EntryCellRenderer {
      @Override
      void render(FlutterLogEntry entry) {
        final FlutterLog.Level level = FlutterLog.Level.forValue(entry.getLevel());
        final String value = level.toDisplayString();
        appendStyled(entry, value);
      }
    }

    private class CategoryCellRenderer extends EntryCellRenderer {
      @Override
      void render(FlutterLogEntry entry) {
        appendStyled(entry, entry.getCategory());
      }
    }

    private class MessageCellRenderer extends EntryCellRenderer {
      @NotNull
      private final FlutterApp app;
      @NotNull
      private final Filter[] filters;

      MessageCellRenderer(@NotNull FlutterApp app) {
        this.app = app;
        filters = createMessageFilters().toArray(new Filter[0]);
      }

      @NotNull
      private List<Filter> createMessageFilters() {
        final List<Filter> filters = new ArrayList<>();
        if (app.getModule() != null) {
          filters.add(new FlutterConsoleFilter(app.getModule()));
        }
        filters.addAll(Arrays.asList(
          new DartConsoleFilter(app.getProject(), app.getProject().getBaseDir()),
          new UrlFilter()
        ));
        return filters;
      }

      @Override
      void render(FlutterLogEntry entry) {
        // TODO(pq): SpeedSearchUtil.applySpeedSearchHighlighting
        // TODO(pq): setTooltipText
        final String message = entry.getMessage();
        if (StringUtils.isEmpty(message)) {
          return;
        }
        final List<Filter.ResultItem> resultItems = new ArrayList<>();
        for (Filter filter : filters) {
          final Filter.Result result = filter.applyFilter(message, message.length());
          if (result == null) {
            continue;
          }
          resultItems.addAll(result.getResultItems());
        }
        resultItems.sort(Comparator.comparingInt(Filter.ResultItem::getHighlightStartOffset));

        int cursor = 0;
        for (Filter.ResultItem item : resultItems) {
          final HyperlinkInfo hyperlinkInfo = item.getHyperlinkInfo();
          if (hyperlinkInfo != null) {
            final int start = item.getHighlightStartOffset();
            final int end = item.getHighlightEndOffset();
            // append leading text.
            if (cursor < start) {
              appendStyled(entry, message.substring(cursor, start));
            }
            // TODO(pq): re-style hyperlinks?
            append(message.substring(start, end), SimpleTextAttributes.LINK_ATTRIBUTES, hyperlinkInfo);
            cursor = end;
          }
        }

        // append trailing text
        if (cursor < message.length()) {
          appendStyled(entry, message.substring(cursor));
        }
      }
    }

    private final List<Column> columns = new ArrayList<>();

    private final FlutterApp app;
    @NotNull private final EntryModel entryModel;
    boolean updateVisibility;
    private List<Column> visible;
    private ArrayList<TableColumn> tableColumns;
    private TreeTable treeTable;

    ColumnModel(@NotNull FlutterApp app, @NotNull EntryModel entryModel) {
      this.app = app;
      this.entryModel = entryModel;
      columns.addAll(Arrays.asList(
        new Column(TIME, new TimeCellRenderer()),
        new Column(SEQUENCE, new SequenceCellRenderer()),
        new Column(LEVEL, new LevelCellRenderer()),
        new Column(CATEGORY, new CategoryCellRenderer()),
        new Column(MESSAGE, new MessageCellRenderer(app))
      ));
      // Cache for quick access.
      visible = columns.stream().filter(c -> c.show).collect(Collectors.toList());
    }

    void show(String column, boolean show) {
      columns.forEach(c -> {
        if (Objects.equals(column, c.getName())) {
          c.show = show;
        }
      });

      updateVisibility = true;

      // Cache for quick access.
      visible = columns.stream().filter(c -> c.show).collect(Collectors.toList());
    }

    void update() {
      if (updateVisibility) {
        // Clear all.
        Collections.list(treeTable.getColumnModel().getColumns()).forEach(c -> treeTable.removeColumn(c));

        // Add back what's appropriate.
        if (isShowing(TIME)) {
          treeTable.addColumn(tableColumns.get(0));
        }
        if (isShowing(SEQUENCE)) {
          treeTable.addColumn(tableColumns.get(1));
        }
        if (isShowing(LEVEL)) {
          treeTable.addColumn(tableColumns.get(2));
        }
        if (isShowing(CATEGORY)) {
          treeTable.addColumn(tableColumns.get(3));
        }

        tableColumns.subList(4, tableColumns.size()).forEach(c -> treeTable.addColumn(c));
      }

      updateVisibility = false;
    }

    ColumnInfo[] getInfos() {
      return columns.toArray(new ColumnInfo[0]);
    }

    public TableCellRenderer getRenderer(int column) {
      return visible.get(column).renderer;
    }

    public boolean isShowing(String column) {
      for (Column c : columns) {
        if (Objects.equals(column, c.getName())) {
          return c.show;
        }
      }
      return false;
    }

    public void init(TreeTable table) {
      treeTable = table;
      tableColumns = Collections.list(table.getColumnModel().getColumns());
    }
  }

  static class TreeModel extends ListTreeTableModelOnColumns {
    interface UpdateCallback {
      void updated();
    }

    @NotNull
    private final ColumnModel columns;
    @NotNull
    private final FlutterLog log;
    @NotNull
    private final Alarm uiThreadAlarm;
    boolean autoScrollToEnd;
    // Cached for hide and restore (because *sigh* Swing).
    private List<TableColumn> tableColumns;
    private JScrollPane scrollPane;
    private TreeTable treeTable;
    private boolean color;
    private UpdateCallback updateCallback;

    @NotNull
    private final FlutterLogPreferences logPreferences;

    public TreeModel(@NotNull FlutterApp app,
                     @NotNull EntryModel entryModel,
                     @NotNull Disposable parent) {
      this(new ColumnModel(app, entryModel), parent, FlutterLogPreferences.getInstance(app.getProject()));
    }

    private TreeModel(@NotNull ColumnModel columns, @NotNull Disposable parent, @NotNull FlutterLogPreferences logPreferences) {
      super(new LogRootTreeNode(), columns.getInfos());
      this.logPreferences = logPreferences;

      this.log = columns.app.getFlutterLog();
      this.columns = columns;

      // Scroll to end by default.
      autoScrollToEnd = true;

      setShowSequenceNumbers(false);

      uiThreadAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, parent);
    }

    public void setUpdateCallback(UpdateCallback updateCallback) {
      this.updateCallback = updateCallback;
    }

    @NotNull
    public FlutterLogPreferences getLogPreferences() {
      return logPreferences;
    }

    void scrollToEnd() {
      if (scrollPane != null) {
        final JScrollBar vertical = scrollPane.getVerticalScrollBar();
        vertical.setValue(vertical.getMaximum());
      }
    }

    void update() {
      columns.update();

      reload(getRoot());
      treeTable.updateUI();
      if (updateCallback != null) {
        updateCallback.updated();
      }

      if (autoScrollToEnd) {
        uiThreadAlarm.addRequest(this::scrollToEnd, 100);
      }
    }

    @Override
    public LogRootTreeNode getRoot() {
      return (LogRootTreeNode)super.getRoot();
    }

    public void setScrollPane(JScrollPane scrollPane) {
      this.scrollPane = scrollPane;
    }

    @Override
    public void setTree(JTree tree) {
      super.setTree(tree);
      treeTable = ((TreeTableTree)tree).getTreeTable();
      columns.init(treeTable);
    }

    public void clearEntries() {
      log.clear();
      getRoot().removeAllChildren();
      update();
    }

    public void appendNodes(List<FlutterLogEntry> entries) {
      if (treeTable == null || uiThreadAlarm.isDisposed()) {
        return;
      }

      uiThreadAlarm.addRequest(() -> {
        final MutableTreeNode root = getRoot();
        entries.forEach(entry -> insertNodeInto(new FlutterEventNode(entry), root, root.getChildCount()));
        update();
      }, 10);
    }

    public boolean shouldShowTimestamps() {
      return columns.isShowing(TIME);
    }

    public void setShowTimestamps(boolean show) {
      columns.show(TIME, show);
    }

    public boolean shouldShowSequenceNumbers() {
      return columns.isShowing(SEQUENCE);
    }

    public void setShowSequenceNumbers(boolean show) {
      columns.show(SEQUENCE, show);
    }

    public boolean shouldShowLogLevels() {
      return columns.isShowing(LEVEL);
    }

    public void setShowLogLevels(boolean show) {
      columns.show(LEVEL, show);
    }

    public void setShowCategories(boolean show) {
      columns.show(CATEGORY, show);
    }

    public boolean shouldShowCategories() {
      return columns.isShowing(CATEGORY);
    }

    void updateFromPreferences(@NotNull FlutterLogPreferences flutterLogPreferences) {
      setShowTimestamps(flutterLogPreferences.isShowTimestamp());
      setShowSequenceNumbers(flutterLogPreferences.isShowSequenceNumbers());
      setShowLogLevels(flutterLogPreferences.isShowLogLevel());
      setShowCategories(flutterLogPreferences.isShowLogCategory());
    }
  }

  static class LogRootTreeNode extends DefaultMutableTreeNode {

  }

  static class FlutterEventNode extends DefaultMutableTreeNode {
    final FlutterLogEntry entry;

    FlutterEventNode(FlutterLogEntry entry) {
      this.entry = entry;
    }

    public void describeTo(@NotNull StringBuilder buffer) {
      buffer
        .append(TIMESTAMP_FORMAT.format(entry.getTimestamp()))
        .append(" ").append(entry.getSequenceNumber())
        .append(" ").append(entry.getLevelName())
        .append(" ").append(entry.getCategory())
        .append(" ").append(entry.getMessage());
      if (!entry.getMessage().endsWith("\n")) {
        buffer.append("\n");
      }
    }
  }

  public static class EntryFilter {
    @NotNull
    private final FlutterLogFilterPanel.FilterParam filterParam;

    public EntryFilter(@NotNull FlutterLogFilterPanel.FilterParam filterParam) {
      this.filterParam = filterParam;
    }

    public boolean accept(@NotNull FlutterLogEntry entry) {
      if (entry.getLevel() < filterParam.getLogLevel().value) {
        return false;
      }
      final String text = filterParam.getExpression();
      if (text == null) {
        return true;
      }
      final boolean isMatchCase = filterParam.isMatchCase();
      final String standardText = isMatchCase ? text : text.toLowerCase();
      final String standardMessage = isMatchCase ? entry.getMessage() : entry.getMessage().toLowerCase();
      final String standardCategory = isMatchCase ? entry.getCategory() : entry.getCategory().toLowerCase();
      if (acceptByCheckingRegexOption(standardCategory, standardText)) {
        return true;
      }
      return acceptByCheckingRegexOption(standardMessage, standardText);
    }

    private boolean acceptByCheckingRegexOption(@NotNull String message, @NotNull String text) {
      if (filterParam.isRegex()) {
        return message.matches("(?s).*" + text + ".*");
      }
      return message.contains(text);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      final EntryFilter filter = (EntryFilter)o;
      return Objects.equals(filterParam, filter.filterParam);
    }

    @Override
    public int hashCode() {
      return Objects.hash(filterParam);
    }
  }

  public interface EntryModel {
    SimpleTextAttributes style(@Nullable FlutterLogEntry entry, int attributes);
  }

  public interface EventCountListener extends EventListener {
    void updated(int total, int filtered);
  }

  private final EventDispatcher<EventCountListener> countDispatcher = EventDispatcher.create(EventCountListener.class);
  private final TreeModel model;
  private FlutterLogFilterPanel.FilterParam filter;
  @NotNull
  private final TableRowSorter<TableModel> rowSorter;
  @NotNull
  private final FlutterLogEntryPopup flutterLogPopup;

  public FlutterLogTree(@NotNull FlutterApp app,
                        @NotNull EntryModel entryModel,
                        @NotNull Disposable parent) {
    this(new TreeModel(app, entryModel, parent));
  }

  FlutterLogTree(@NotNull TreeModel model) {
    super(model);
    model.setTree(this.getTree());
    this.model = model;
    final TableModel tableModel = getModel();
    rowSorter = new TableRowSorter<>(new FlutterTreeTableModel(this));
    setRowSorter(rowSorter);
    registerCopyHandler();
    flutterLogPopup = new FlutterLogEntryPopup();
    addMouseListener(new SimpleMouseListener() {
      @Override
      public void mouseDoublePressed(MouseEvent e) {
        super.mouseDoublePressed(e);
        final String selectedLog = getSelectedLog();
        if (StringUtils.isNotEmpty(selectedLog)) {
          flutterLogPopup.showLogDialog(selectedLog);
        }
      }
    });
    rowSorter.addRowSorterListener(e -> updateCounter());
  }

  @NotNull
  private String getSelectedLog() {
    final int[] rows = getSelectedRows();
    final StringBuilder logBuilder = new StringBuilder();
    for (int row : rows) {
      final int realRow = convertRowIndexToModel(row);
      final TreePath path = getTree().getPathForRow(realRow);
      final Object pathComponent = path.getLastPathComponent();
      if (pathComponent instanceof FlutterLogTree.FlutterEventNode) {
        ((FlutterLogTree.FlutterEventNode)pathComponent).describeTo(logBuilder);
      }
    }
    return logBuilder.toString();
  }

  public void sendSelectedLogsToClipboard() {
    ApplicationManager.getApplication().invokeLater(() -> {
      final String log = getSelectedLog();
      if (StringUtils.isNotEmpty(log)) {
        final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        final StringSelection selection = new StringSelection(log);
        clipboard.setContents(selection, selection);
      }
    });
  }

  private void registerCopyHandler() {
    final AnAction actionCopy = ActionManager.getInstance().getAction(IdeActions.ACTION_COPY);
    final ShortcutSet copyShortcutSet = actionCopy.getShortcutSet();
    final String copyCommand = "flutter.log.copyCommand";

    Arrays.stream(copyShortcutSet.getShortcuts())
      .filter(shortcut -> shortcut instanceof KeyboardShortcut)
      .map(shortcut -> (KeyboardShortcut)shortcut)
      .flatMap(shortcut -> Stream.of(shortcut.getFirstKeyStroke(), shortcut.getSecondKeyStroke()))
      .filter(Objects::nonNull)
      .forEach(stroke -> getInputMap().put(stroke, copyCommand));

    getActionMap().put(copyCommand, new AbstractAction() {
      @Override
      public void actionPerformed(ActionEvent e) {
        sendSelectedLogsToClipboard();
      }
    });
  }

  public void addListener(@NotNull EventCountListener listener, @NotNull Disposable parent) {
    countDispatcher.addListener(listener, parent);
  }

  public void removeListener(@NotNull EventCountListener listener) {
    countDispatcher.removeListener(listener);
  }

  @NotNull
  TreeModel getLogTreeModel() {
    return model;
  }

  @Override
  public TableCellRenderer getCellRenderer(int row, int column) {
    return model.columns.getRenderer(column);
  }

  public void setFilter(@NotNull FlutterLogFilterPanel.FilterParam filter) {
    if (Objects.equals(this.filter, filter)) {
      return;
    }
    this.filter = filter;
    updateRowFilter(filter);
    updateCounter();
  }

  private void updateRowFilter(@NotNull FlutterLogFilterPanel.FilterParam filter) {
    final String nonNullExpression = filter.getExpression() == null ? "" : filter.getExpression();
    final String quoteRegex = filter.isRegex() ? nonNullExpression : Pattern.quote(nonNullExpression);

    final String matchCaseRegex = filter.isMatchCase() ? "" : "(?i)";
    final String standardRegex = matchCaseRegex + "(?s).*" + quoteRegex + ".*";
    final RowFilter<TableModel, Object> logMessageFilter;
    final RowFilter<TableModel, Object> logLevelFilter;
    try {
      logMessageFilter = RowFilter.regexFilter(
        standardRegex,
        FlutterTreeTableModel.ColumnIndex.MESSAGE.index, FlutterTreeTableModel.ColumnIndex.CATEGORY.index
      );
      logLevelFilter = RowFilter.regexFilter(LOG_LEVEL_FILTER.get(filter.getLogLevel()), FlutterTreeTableModel.ColumnIndex.LOG_LEVEL.index);
    }
    catch (PatternSyntaxException e) {
      return;
    }
    rowSorter.setRowFilter(RowFilter.andFilter(Arrays.asList(logMessageFilter, logLevelFilter)));
  }

  void append(@NotNull FlutterLogEntry entry) {
    if (entry.getKind() == FlutterLogEntry.Kind.RELOAD && model.getLogPreferences().isClearOnReload() ||
        entry.getKind() == FlutterLogEntry.Kind.RESTART && model.getLogPreferences().isClearOnRestart()
    ) {
      model.clearEntries();
    }
    model.appendNodes(Collections.singletonList(entry));
  }

  private void updateCounter() {
    final int total = rowSorter.getModelRowCount();
    final int filtered = total - rowSorter.getViewRowCount();
    countDispatcher.getMulticaster().updated(total, filtered);
  }

  public void clearEntries() {
    model.clearEntries();
  }

  private static class SimpleMouseListener implements MouseListener {
    @Override
    public void mouseClicked(MouseEvent e) {
    }

    @Override
    public void mousePressed(MouseEvent e) {
      if (e.getClickCount() == 2) {
        mouseDoublePressed(e);
      }
    }

    public void mouseDoublePressed(MouseEvent e) {
    }

    @Override
    public void mouseReleased(MouseEvent e) {
    }

    @Override
    public void mouseEntered(MouseEvent e) {
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }
  }
}
