/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.vove7.eventbus_nav.action;

import com.intellij.CommonBundle;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.codeInsight.hint.HintUtil;
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.find.FindBundle;
import com.intellij.find.FindManager;
import com.intellij.find.FindSettings;
import com.intellij.find.actions.FindUsagesInFileAction;
import com.intellij.find.actions.UsageListCellRenderer;
import com.intellij.find.findUsages.AbstractFindUsagesDialog;
import com.intellij.find.findUsages.FindUsagesHandler;
import com.intellij.find.findUsages.FindUsagesManager;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter;
import com.intellij.find.impl.FindManagerImpl;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.ide.util.gotoByName.ModelDiff;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.DataProvider;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.actionSystem.KeyboardShortcut;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.PopupAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.search.SearchScope;
import com.intellij.ui.*;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.popup.AbstractPopup;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewBundle;
import com.intellij.usages.PsiElementUsageTarget;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;
import com.intellij.usages.UsageInfoToUsageConverter;
import com.intellij.usages.UsageTarget;
import com.intellij.usages.UsageToPsiElementProvider;
import com.intellij.usages.UsageView;
import com.intellij.usages.UsageViewManager;
import com.intellij.usages.UsageViewPresentation;
import com.intellij.usages.UsageViewSettings;
import com.intellij.usages.impl.GroupNode;
import com.intellij.usages.impl.NullUsage;
import com.intellij.usages.impl.UsageGroupingRuleProviderImpl;
import com.intellij.usages.impl.UsageNode;
import com.intellij.usages.impl.UsageViewImpl;
import com.intellij.usages.impl.UsageViewManagerImpl;
import com.intellij.usages.impl.UsageViewTreeModelBuilder;
import com.intellij.usages.rules.UsageFilteringRuleProvider;
import com.intellij.util.Alarm;
import com.intellij.util.PlatformIcons;
import com.intellij.util.Processor;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.ListTableModel;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableColumn;

import cn.vove7.eventbus_nav.filter.Filter;
import cn.vove7.eventbus_nav.ui.CompositeActiveComponent;
import cn.vove7.eventbus_nav.ui.PingEDT;
import cn.vove7.eventbus_nav.ui.ShowUsagesTableCellRenderer;

/**
 * modify by likfe ( https://github.com/likfe/ ) in 2016/09/05
 * <p>
 * add ShowUsagesAction(), if Registering actions in the plugin.xml file,ShowUsagesAction must have ShowUsagesAction()
 */

public class ShowUsagesAction extends AnAction implements PopupAction {
    private static final int USAGES_PAGE_SIZE = 100;

    private Filter filter;
    //0 search receiver; 1 search post
    private int type;

    public static final int TYPE_RECEIVER = 0;
    public static final int TYPE_POST = 1;

    public final static NullUsage MORE_USAGES_SEPARATOR = NullUsage.INSTANCE;
    private static final UsageNode MORE_USAGES_SEPARATOR_NODE = UsageViewImpl.NULL_NODE;

    private static final Comparator<UsageNode> USAGE_NODE_COMPARATOR = (c1, c2) -> {
        if (c1 instanceof StringNode) return 1;
        if (c2 instanceof StringNode) return -1;
        Usage o1 = c1.getUsage();
        Usage o2 = c2.getUsage();
        if (o1 == MORE_USAGES_SEPARATOR) return 1;
        if (o2 == MORE_USAGES_SEPARATOR) return -1;

        VirtualFile v1 = UsageListCellRenderer.getVirtualFile(o1);
        VirtualFile v2 = UsageListCellRenderer.getVirtualFile(o2);
        String name1 = v1 == null ? null : v1.getName();
        String name2 = v2 == null ? null : v2.getName();
        int i = Comparing.compare(name1, name2);
        if (i != 0) return i;

        if (o1 instanceof Comparable && o2 instanceof Comparable) {
            return ((Comparable) o1).compareTo(o2);
        }

        FileEditorLocation loc1 = o1.getLocation();
        FileEditorLocation loc2 = o2.getLocation();
        return Comparing.compare(loc1, loc2);
    };
    private static final Runnable HIDE_HINTS_ACTION = ShowUsagesAction::hideHints;
    @NotNull
    private final UsageViewSettings myUsageViewSettings;
    @Nullable
    private Runnable mySearchEverywhereRunnable;

    public ShowUsagesAction() {
        setInjectedContext(true);
        final UsageViewSettings usageViewSettings = UsageViewSettings.getInstance();
        myUsageViewSettings = new UsageViewSettings();
        myUsageViewSettings.loadState(usageViewSettings);
        myUsageViewSettings.GROUP_BY_FILE_STRUCTURE = false;
        myUsageViewSettings.GROUP_BY_MODULE = false;
        myUsageViewSettings.GROUP_BY_PACKAGE = false;
        myUsageViewSettings.GROUP_BY_USAGE_TYPE = false;
        myUsageViewSettings.GROUP_BY_SCOPE = false;
    }

    public ShowUsagesAction(Filter filter, int type) {
        this.filter = filter;
        this.type = type;
        setInjectedContext(true);

        final UsageViewSettings usageViewSettings = UsageViewSettings.getInstance();
        myUsageViewSettings = new UsageViewSettings();
        myUsageViewSettings.loadState(usageViewSettings);
        myUsageViewSettings.GROUP_BY_FILE_STRUCTURE = false;
        myUsageViewSettings.GROUP_BY_MODULE = false;
        myUsageViewSettings.GROUP_BY_PACKAGE = false;
        myUsageViewSettings.GROUP_BY_USAGE_TYPE = false;
        myUsageViewSettings.GROUP_BY_SCOPE = false;
    }


    @Override
    public void actionPerformed(AnActionEvent e) {
        final Project project = e.getData(PlatformDataKeys.PROJECT);
        if (project == null) return;

        Runnable searchEverywhere = mySearchEverywhereRunnable;
        mySearchEverywhereRunnable = null;
        hideHints();

        if (searchEverywhere != null) {
            searchEverywhere.run();
            return;
        }

        final RelativePoint popupPosition = JBPopupFactory.getInstance().guessBestPopupLocation(e.getDataContext());
        PsiDocumentManager.getInstance(project).commitAllDocuments();
        FeatureUsageTracker.getInstance().triggerFeatureUsed("navigation.goto.usages");

        UsageTarget[] usageTargets = e.getData(UsageView.USAGE_TARGETS_KEY);
        final Editor editor = e.getData(PlatformDataKeys.EDITOR);
        if (usageTargets == null) {
            chooseAmbiguousTargetAndPerform(project, editor, element -> {
                startFindUsages(element, popupPosition, editor, USAGES_PAGE_SIZE);
                return false;
            });
        } else {
            PsiElement element = ((PsiElementUsageTarget) usageTargets[0]).getElement();
            if (element != null) {
                startFindUsages(element, popupPosition, editor, USAGES_PAGE_SIZE);
            }
        }
    }

    static void chooseAmbiguousTargetAndPerform(@NotNull final Project project,
                                                final Editor editor,
                                                @NotNull PsiElementProcessor<PsiElement> processor) {
        if (editor == null) {
            Messages.showMessageDialog(project, FindBundle.message("find.no.usages.at.cursor.error"),
                    CommonBundle.getErrorTitle(), Messages.getErrorIcon());
        } else {
            int offset = editor.getCaretModel().getOffset();
            boolean chosen = GotoDeclarationAction.chooseAmbiguousTarget(editor, offset, processor,
                    FindBundle.message("find.usages.ambiguous.title", "crap"), null);
            if (!chosen) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (editor.isDisposed() || !editor.getComponent().isShowing()) return;
                    HintManager.getInstance().showErrorHint(editor, FindBundle.message("find.no.usages.at.cursor.error"));
                }, project.getDisposed());
            }
        }
    }


    private static void hideHints() {
        HintManager.getInstance().hideHints(HintManager.HIDE_BY_ANY_KEY, false, false);
    }

    public void startFindUsages(@NotNull PsiElement element, @NotNull RelativePoint popupPosition, Editor editor, int maxUsages) {
        startFindUsages(new PsiElement[]{element}, popupPosition, editor, maxUsages);
    }

    public void startFindUsages(@NotNull PsiElement[] elements, @NotNull RelativePoint popupPosition, Editor editor, int maxUsages) {
        Project project = elements[0].getProject();
        FindUsagesManager findUsagesManager = ((FindManagerImpl) FindManager.getInstance(project)).getFindUsagesManager();
        FindUsagesHandler handler = findUsagesManager.getNewFindUsagesHandler(elements[0], false);
        if (handler == null) return;
        showElementUsages(handler, editor, popupPosition, maxUsages, getDefaultOptions(handler), elements);
    }

    @NotNull
    private static FindUsagesOptions getDefaultOptions(@NotNull FindUsagesHandler handler) {
        FindUsagesOptions options = handler.getFindUsagesOptions(DataManager.getInstance().getDataContext());
        // by default, scope in FindUsagesOptions is copied from the FindSettings, but we need a default one
        //options.searchScope = FindUsagesManager.getMaximalScope(handler);
        //options.searchScope = GlobalSearchScope.allScope(handler.getProject());
        options.searchScope = GlobalSearchScope.projectScope(handler.getProject());
        //FindUsagesManager.getMaximalScope(handler);
        return options;
    }

    private void showElementUsages(
            @NotNull final FindUsagesHandler handler,
            final Editor editor,
            @NotNull final RelativePoint popupPosition,
            final int maxUsages,
            @NotNull final FindUsagesOptions options,
            PsiElement[] elements
    ) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        final UsageViewSettings usageViewSettings = UsageViewSettings.getInstance();
        final UsageViewSettings savedGlobalSettings = new UsageViewSettings();

        savedGlobalSettings.loadState(usageViewSettings);
        usageViewSettings.loadState(myUsageViewSettings);

        final Project project = handler.getProject();
        UsageViewManager manager = UsageViewManager.getInstance(project);
        FindUsagesManager findUsagesManager = ((FindManagerImpl) FindManager.getInstance(project)).getFindUsagesManager();

        final UsageViewPresentation presentation = findUsagesManager.createPresentation(handler, options);
        presentation.setDetachedMode(true);
        final UsageViewImpl usageView = (UsageViewImpl) manager.createUsageView(UsageTarget.EMPTY_ARRAY, Usage.EMPTY_ARRAY, presentation, null);

        Disposer.register(usageView, () -> {
            myUsageViewSettings.loadState(usageViewSettings);
            usageViewSettings.loadState(savedGlobalSettings);
        });

        final List<Usage> usages = new ArrayList<Usage>();
        final Set<UsageNode> visibleNodes = new LinkedHashSet<>();
        UsageInfoToUsageConverter.TargetElementsDescriptor descriptor =
                new UsageInfoToUsageConverter.TargetElementsDescriptor(handler.getPrimaryElements(), handler.getSecondaryElements());

        final MyTable table = new MyTable();
        final AsyncProcessIcon processIcon = new AsyncProcessIcon("xxx");
        boolean hadMoreSeparator = visibleNodes.remove(MORE_USAGES_SEPARATOR_NODE);
        if (hadMoreSeparator) {
            usages.add(MORE_USAGES_SEPARATOR);
            visibleNodes.add(MORE_USAGES_SEPARATOR_NODE);
        }

        addUsageNodes(usageView.getRoot(), usageView, new ArrayList<>());

        ScrollingUtil.installActions(table);

        final List<UsageNode> data = collectData(usages, visibleNodes, usageView, presentation);
        setTableModel(table, usageView, data);

        SpeedSearchBase<JTable> speedSearch = new MySpeedSearch(table);
        speedSearch.setComparator(new SpeedSearchComparator(false));

        final JBPopup popup = createUsagePopup(usages, descriptor, visibleNodes, handler, editor, popupPosition,
                maxUsages, usageView, options, table, presentation, processIcon, hadMoreSeparator);

        Disposer.register(popup, usageView);

        // show popup only if find usages takes more than 300ms, otherwise it would flicker needlessly
        Alarm alarm = new Alarm(usageView);
        alarm.addRequest(() -> showPopupIfNeedTo(popup, popupPosition), 300);

        final PingEDT pingEDT = new PingEDT("Rebuild popup in EDT", o -> popup.isDisposed(), 100, () -> {
            if (popup.isDisposed()) return;

            final List<UsageNode> nodes = new ArrayList<>();
            List<Usage> copy;
            synchronized (usages) {
                // open up popup as soon as several usages 've been found
                if (!popup.isVisible() && (usages.size() <= 1 || !showPopupIfNeedTo(popup, popupPosition))) {
                    return;
                }
                addUsageNodes(usageView.getRoot(), usageView, nodes);
                copy = new ArrayList<>(usages);
            }

            rebuildPopup(usageView, copy, nodes, table, popup, presentation, popupPosition, !processIcon.isDisposed());
        });

        final MessageBusConnection messageBusConnection = project.getMessageBus().connect(usageView);
        messageBusConnection.subscribe(UsageFilteringRuleProvider.RULES_CHANGED, pingEDT::ping);

        Processor<Usage> collect = new Processor<Usage>() {
            private final UsageTarget[] myUsageTarget = {new PsiElement2UsageTargetAdapter(handler.getPsiElement())};

            @Override
            public boolean process(@NotNull Usage usage) {
                synchronized (usages) {
                    if (!filter.shouldShow(usage)) return true;
                    if (visibleNodes.size() >= maxUsages) return false;
                    if (UsageViewManager.isSelfUsage(usage, myUsageTarget)) {
                        return true;
                    }

                    Usage usageToAdd = transform(usage);
                    if (usageToAdd == null) return true;

                    UsageNode node = usageView.doAppendUsage(usageToAdd);
                    usages.add(usageToAdd);
                    if (node != null) {
                        visibleNodes.add(node);
                        boolean continueSearch = true;
                        if (visibleNodes.size() == maxUsages) {
                            visibleNodes.add(MORE_USAGES_SEPARATOR_NODE);
                            usages.add(MORE_USAGES_SEPARATOR);
                            continueSearch = false;
                        }
                        pingEDT.ping();

                        return continueSearch;
                    }
                    return true;
                }
            }
        };

        useHandler(handler, editor, popupPosition, maxUsages, options, usages, visibleNodes, pingEDT, processIcon, popup, presentation, collect, elements);

    }

    private void useHandler(@NotNull final FindUsagesHandler handler,
                            final Editor editor,
                            @NotNull final RelativePoint popupPosition,
                            final int maxUsages,
                            @NotNull final FindUsagesOptions options,
                            List<Usage> usages,
                            Set<UsageNode> visibleNodes,
                            PingEDT pingEDT,
                            AsyncProcessIcon processIcon,
                            JBPopup popup,
                            UsageViewPresentation presentation,
                            Processor<Usage> collect,
                            PsiElement[] elements) {

        final ProgressIndicator indicator = FindUsagesManager.startProcessUsages(handler,
                elements, handler.getPrimaryElements(), collect, options, () -> ApplicationManager.getApplication().invokeLater(() -> {
                    Disposer.dispose(processIcon);
                    Container parent = processIcon.getParent();
                    parent.remove(processIcon);
                    parent.repaint();
                    pingEDT.ping(); // repaint title
                    synchronized (usages) {
                        if (visibleNodes.isEmpty()) {
                            if (usages.isEmpty()) {
                                String text = UsageViewBundle.message("no.usages.found.in", searchScopePresentableName(options, handler.getProject()));
                                showHint(text, editor, popupPosition, handler, maxUsages, options);
                                popup.cancel();
                            } else {
                                // all usages filtered out
                            }
                        } else if (visibleNodes.size() == 1) {
                            if (usages.size() == 1) {
                                //the only usage
                                Usage usage = visibleNodes.iterator().next().getUsage();
                                usage.navigate(true);
                                //String message = UsageViewBundle.message("show.usages.only.usage", searchScopePresentableName(options, project));
                                //navigateAndHint(usage, message, handler, popupPosition, maxUsages, options);
                                popup.cancel();
                            } else {
                                assert usages.size() > 1 : usages;
                                // usage view can filter usages down to one
                                Usage visibleUsage = visibleNodes.iterator().next().getUsage();
                                if (areAllUsagesInOneLine(visibleUsage, usages)) {
                                    String hint = UsageViewBundle.message("all.usages.are.in.this.line", usages.size(), searchScopePresentableName(options, handler.getProject()));
                                    navigateAndHint(visibleUsage, hint, handler, popupPosition, maxUsages, options);
                                    popup.cancel();
                                }
                            }
                        } else {
                            String title = presentation.getTabText();
                            boolean shouldShowMoreSeparator = visibleNodes.contains(MORE_USAGES_SEPARATOR_NODE);
                            String fullTitle = getFullTitle(usages, title, shouldShowMoreSeparator, visibleNodes.size() - (shouldShowMoreSeparator ? 1 : 0), false);
                            ((AbstractPopup) popup).setCaption(fullTitle);
                        }
                    }
                }, handler.getProject().getDisposed()));
        Disposer.register(popup, indicator::cancel);
    }

    /**
     * 转换usage 显示行
     * 当使用@Subscribe 进行搜索时，显示列不是函数所在行，需要转换至函数Usage
     */
    @SuppressWarnings("ConstantConditions")
    private Usage transform(@NotNull Usage usage) {
        if (type == TYPE_RECEIVER) {
            PsiElement ele = ((UsageInfo2UsageAdapter) usage).getElement();
            if (ele instanceof PsiJavaCodeReferenceElement) {
                PsiElement method = ((PsiMethod) ele.getContext().getContext().getContext()).getIdentifyingElement();
                return new UsageInfo2UsageAdapter(new UsageInfo(method));
            } else if (ele instanceof KtNameReferenceExpression) {
                PsiElement method = ((KtNamedFunction) ele.getParent().getParent().getParent().getParent().getParent().getParent()).getNameIdentifier();
                return new UsageInfo2UsageAdapter(new UsageInfo(method));
            }
        }
        return usage;
    }

    @NotNull
    private static UsageNode createStringNode(@NotNull final Object string) {
        return new StringNode(string);
    }

    private static class MyModel extends ListTableModel<UsageNode> implements ModelDiff.Model<Object> {
        private MyModel(@NotNull List<UsageNode> data, int cols) {
            super(cols(cols), data, 0);
        }

        @NotNull
        private static ColumnInfo[] cols(int cols) {
            ColumnInfo<UsageNode, UsageNode> o = new ColumnInfo<UsageNode, UsageNode>("") {
                @Nullable
                @Override
                public UsageNode valueOf(UsageNode node) {
                    return node;
                }
            };
            List<ColumnInfo<UsageNode, UsageNode>> list = Collections.nCopies(cols, o);
            return list.toArray(new ColumnInfo[list.size()]);
        }

        @Override
        public void addToModel(int idx, Object element) {
            UsageNode node = element instanceof UsageNode ? (UsageNode) element : createStringNode(element);

            if (idx < getRowCount()) {
                insertRow(idx, node);
            } else {
                addRow(node);
            }
        }

        @Override
        public void removeRangeFromModel(int start, int end) {
            for (int i = end; i >= start; i--) {
                removeRow(i);
            }
        }
    }

    private static boolean showPopupIfNeedTo(@NotNull JBPopup popup, @NotNull RelativePoint popupPosition) {
        if (!popup.isDisposed() && !popup.isVisible()) {
            popup.show(popupPosition);
            return true;
        } else {
            return false;
        }
    }

    private void showHint(@NotNull String text,
                          @Nullable final Editor editor,
                          @NotNull final RelativePoint popupPosition,
                          @NotNull FindUsagesHandler handler,
                          int maxUsages,
                          @NotNull FindUsagesOptions options) {
        JComponent label = createHintComponent(text, handler, popupPosition, editor, HIDE_HINTS_ACTION, maxUsages, options);
        if (editor == null || editor.isDisposed()) {
            HintManager.getInstance().showHint(label, popupPosition, HintManager.HIDE_BY_ANY_KEY |
                    HintManager.HIDE_BY_TEXT_CHANGE | HintManager.HIDE_BY_SCROLLING, 0);
        } else {
            HintManager.getInstance().showInformationHint(editor, label);
        }
    }

    @NotNull
    private JComponent createHintComponent(@NotNull String text,
                                           @NotNull final FindUsagesHandler handler,
                                           @NotNull final RelativePoint popupPosition,
                                           final Editor editor,
                                           @NotNull final Runnable cancelAction,
                                           final int maxUsages,
                                           @NotNull final FindUsagesOptions options) {
        JComponent label = HintUtil.createInformationLabel(suggestSecondInvocation(options, handler, text + "&nbsp;"));
        InplaceButton button = createSettingsButton(handler, popupPosition, editor, maxUsages, cancelAction);

        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            public void addNotify() {
                mySearchEverywhereRunnable = () -> searchEverywhere(options, handler, editor, popupPosition, maxUsages);
                super.addNotify();
            }

            @Override
            public void removeNotify() {
                mySearchEverywhereRunnable = null;
                super.removeNotify();
            }
        };
        button.setBackground(label.getBackground());
        panel.setBackground(label.getBackground());
        label.setOpaque(false);
        label.setBorder(null);
        panel.setBorder(HintUtil.createHintBorder());
        panel.add(label, BorderLayout.CENTER);
        panel.add(button, BorderLayout.EAST);
        return panel;
    }

    @NotNull
    private InplaceButton createSettingsButton(@NotNull final FindUsagesHandler handler,
                                               @NotNull final RelativePoint popupPosition,
                                               final Editor editor,
                                               final int maxUsages,
                                               @NotNull final Runnable cancelAction) {
        String shortcutText = "";
        KeyboardShortcut shortcut = UsageViewImpl.getShowUsagesWithSettingsShortcut();
        if (shortcut != null) {
            shortcutText = "(" + KeymapUtil.getShortcutText(shortcut) + ")";
        }
        return new InplaceButton("Settings..." + shortcutText, AllIcons.General.Settings, e -> {
            SwingUtilities.invokeLater(() -> showDialogAndFindUsages(handler, popupPosition, editor, maxUsages));
            cancelAction.run();
        });
    }

    private void showDialogAndFindUsages(@NotNull FindUsagesHandler handler,
                                         @NotNull RelativePoint popupPosition,
                                         Editor editor,
                                         int maxUsages) {
        AbstractFindUsagesDialog dialog = handler.getFindUsagesDialog(false, false, false);
        dialog.show();
        if (dialog.isOK()) {
            dialog.calcFindUsagesOptions();
            showElementUsages(handler, editor, popupPosition, maxUsages, getDefaultOptions(handler), handler.getPrimaryElements());
        }
    }

    private static String searchScopePresentableName(@NotNull FindUsagesOptions options, @NotNull Project project) {
        return notNullizeScope(options, project).getDisplayName();
    }

    @NotNull
    private static SearchScope notNullizeScope(@NotNull FindUsagesOptions options, @NotNull Project project) {
        SearchScope scope = options.searchScope;
        //scope
        if (scope == null) return ProjectScope.getProjectScope(project);
        return scope;
    }

    @NotNull
    private JBPopup createUsagePopup(@NotNull final List<Usage> usages,
                                     @NotNull final UsageInfoToUsageConverter.TargetElementsDescriptor descriptor,
                                     @NotNull Set<UsageNode> visibleNodes,
                                     @NotNull final FindUsagesHandler handler,
                                     final Editor editor,
                                     @NotNull final RelativePoint popupPosition,
                                     final int maxUsages,
                                     @NotNull final UsageViewImpl usageView,
                                     @NotNull final FindUsagesOptions options,
                                     @NotNull final JTable table,
                                     @NotNull final UsageViewPresentation presentation,
                                     @NotNull final AsyncProcessIcon processIcon,
                                     boolean hadMoreSeparator) {
        table.setRowHeight(PlatformIcons.CLASS_ICON.getIconHeight() + 2);
        table.setShowGrid(false);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setTableHeader(null);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setIntercellSpacing(new Dimension(0, 0));

        PopupChooserBuilder builder = new PopupChooserBuilder(table);
        final String title = presentation.getTabText();
        if (title != null) {
            String result = getFullTitle(usages, title, hadMoreSeparator, visibleNodes.size() - 1, true);
            builder.setTitle(result);
            builder.setAdText(getSecondInvocationTitle(options, handler));
        }

        builder.setMovable(true).setResizable(true);
        builder.setItemChoosenCallback(() -> {
            int[] selected = table.getSelectedRows();
            for (int i : selected) {
                Object value = table.getValueAt(i, 0);
                if (value instanceof UsageNode) {
                    Usage usage = ((UsageNode) value).getUsage();
                    if (usage == MORE_USAGES_SEPARATOR) {
                        appendMoreUsages(editor, popupPosition, handler, maxUsages);
                        return;
                    }
                    navigateAndHint(usage, null, handler, popupPosition, maxUsages, options);
                }
            }
        });
        final JBPopup[] popup = new JBPopup[1];

        KeyboardShortcut shortcut = UsageViewImpl.getShowUsagesWithSettingsShortcut();
        if (shortcut != null) {
            new DumbAwareAction() {
                @Override
                public void actionPerformed(AnActionEvent e) {
                    popup[0].cancel();
                    showDialogAndFindUsages(handler, popupPosition, editor, maxUsages);
                }
            }.registerCustomShortcutSet(new CustomShortcutSet(shortcut.getFirstKeyStroke()), table);
        }
        shortcut = getShowUsagesShortcut();
        if (shortcut != null) {
            new DumbAwareAction() {
                @Override
                public void actionPerformed(AnActionEvent e) {
                    popup[0].cancel();
                    searchEverywhere(options, handler, editor, popupPosition, maxUsages);
                }
            }.registerCustomShortcutSet(new CustomShortcutSet(shortcut.getFirstKeyStroke()), table);
        }

        InplaceButton settingsButton = createSettingsButton(handler, popupPosition, editor, maxUsages, () -> popup[0].cancel());

        ActiveComponent spinningProgress = new ActiveComponent() {
            @Override
            public void setActive(boolean active) {
            }

            @Override
            public JComponent getComponent() {
                return processIcon;
            }
        };
        builder.setCommandButton(new CompositeActiveComponent(spinningProgress, settingsButton));

        DefaultActionGroup toolbar = new DefaultActionGroup();
        usageView.addFilteringActions(toolbar);

        toolbar.add(UsageGroupingRuleProviderImpl.createGroupByFileStructureAction(usageView));
        toolbar.add(new AnAction("Open Find Usages Toolwindow", "Show all usages in a separate toolwindow", AllIcons.Toolwindows.ToolWindowFind) {
            {
                AnAction action = ActionManager.getInstance().getAction(IdeActions.ACTION_FIND_USAGES);
                setShortcutSet(action.getShortcutSet());
            }

            @Override
            public void actionPerformed(AnActionEvent e) {
                hideHints();
                popup[0].cancel();
                FindUsagesManager findUsagesManager = ((FindManagerImpl) FindManager.getInstance(usageView.getProject())).getFindUsagesManager();

                findUsagesManager.findUsages(handler.getPrimaryElements(), handler.getSecondaryElements(), handler, options,
                        FindSettings.getInstance().isSkipResultsWithOneUsage());
            }
        });

        ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.USAGE_VIEW_TOOLBAR, toolbar, true);
        actionToolbar.setReservePlaceAutoPopupIcon(false);
        final JComponent toolBar = actionToolbar.getComponent();
        toolBar.setOpaque(false);
        builder.setSettingButton(toolBar);

        popup[0] = builder.createPopup();
        JComponent content = popup[0].getContent();

        myWidth = (int) (toolBar.getPreferredSize().getWidth()
                + new JLabel(getFullTitle(usages, title, hadMoreSeparator, visibleNodes.size() - 1, true)).getPreferredSize().getWidth()
                + settingsButton.getPreferredSize().getWidth());
        myWidth = -1;
        for (AnAction action : toolbar.getChildren(null)) {
            action.unregisterCustomShortcutSet(usageView.getComponent());
            action.registerCustomShortcutSet(action.getShortcutSet(), content);
        }

        return popup[0];
    }

    @NotNull
    private static String getFullTitle(@NotNull List<Usage> usages,
                                       @NotNull String title,
                                       boolean hadMoreSeparator,
                                       int visibleNodesCount,
                                       boolean findUsagesInProgress) {
        String s;
        if (hadMoreSeparator) {
            s = "<b>Some</b> " + title + " " + "<b>(Only " + visibleNodesCount + " usages shown" + (findUsagesInProgress ? " so far" : "") + ")</b>";
        } else {
            s = title + " (" + UsageViewBundle.message("usages.n", usages.size()) + (findUsagesInProgress ? " so far" : "") + ")";
        }
        return "<html><nobr>" + s + "</nobr></html>";
    }

    @NotNull
    private static String suggestSecondInvocation(@NotNull FindUsagesOptions options, @NotNull FindUsagesHandler handler, @NotNull String text) {
        final String title = getSecondInvocationTitle(options, handler);

        if (title != null) {
            text += "<br><small>Press " + title + "</small>";
        }
        return "<html><body>" + text + "</body></html>";
    }

    @Nullable
    private static String getSecondInvocationTitle(@NotNull FindUsagesOptions options, @NotNull FindUsagesHandler handler) {
        if (getShowUsagesShortcut() != null) {
            GlobalSearchScope maximalScope = FindUsagesManager.getMaximalScope(handler);
            if (!notNullizeScope(options, handler.getProject()).equals(maximalScope)) {
                return "Press " + KeymapUtil.getShortcutText(getShowUsagesShortcut()) + " again to search in " + maximalScope.getDisplayName();
            }
        }
        return null;
    }

    private void searchEverywhere(@NotNull FindUsagesOptions options,
                                  @NotNull FindUsagesHandler handler,
                                  Editor editor,
                                  @NotNull RelativePoint popupPosition,
                                  int maxUsages) {
        FindUsagesOptions cloned = options.clone();
        cloned.searchScope = FindUsagesManager.getMaximalScope(handler);
        showElementUsages(handler, editor, popupPosition, maxUsages, cloned, handler.getPrimaryElements());
    }

    @Nullable
    private static KeyboardShortcut getShowUsagesShortcut() {
        return ActionManager.getInstance().getKeyboardShortcut("ShowUsages");
    }

    private static int filtered(@NotNull List<Usage> usages, @NotNull UsageViewImpl usageView) {
        int count = 0;
        for (Usage usage : usages) {
            if (!usageView.isVisible(usage)) count++;
        }
        return count;
    }

    private static int getUsageOffset(@NotNull Usage usage) {
        if (!(usage instanceof UsageInfo2UsageAdapter)) return -1;
        PsiElement element = ((UsageInfo2UsageAdapter) usage).getElement();
        if (element == null) return -1;
        return element.getTextRange().getStartOffset();
    }

    private static boolean areAllUsagesInOneLine(@NotNull Usage visibleUsage, @NotNull List<Usage> usages) {
        Editor editor = getEditorFor(visibleUsage);
        if (editor == null) return false;
        int offset = getUsageOffset(visibleUsage);
        if (offset == -1) return false;
        int lineNumber = editor.getDocument().getLineNumber(offset);
        for (Usage other : usages) {
            Editor otherEditor = getEditorFor(other);
            if (otherEditor != editor) return false;
            int otherOffset = getUsageOffset(other);
            if (otherOffset == -1) return false;
            int otherLine = otherEditor.getDocument().getLineNumber(otherOffset);
            if (otherLine != lineNumber) return false;
        }
        return true;
    }

    @NotNull
    private static MyModel setTableModel(@NotNull JTable table,
                                         @NotNull UsageViewImpl usageView,
                                         @NotNull final List<UsageNode> data) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        final int columnCount = calcColumnCount(data);
        MyModel model = table.getModel() instanceof MyModel ? (MyModel) table.getModel() : null;
        if (model == null || model.getColumnCount() != columnCount) {
            model = new MyModel(data, columnCount);
            table.setModel(model);

            ShowUsagesTableCellRenderer renderer = new ShowUsagesTableCellRenderer(usageView);
            for (int i = 0; i < table.getColumnModel().getColumnCount(); i++) {
                TableColumn column = table.getColumnModel().getColumn(i);
                column.setCellRenderer(renderer);
            }
        }
        return model;
    }

    private static int calcColumnCount(@NotNull List<UsageNode> data) {
        return data.isEmpty() || data.get(0) instanceof StringNode ? 1 : 3;
    }

    @NotNull
    private static List<UsageNode> collectData(@NotNull List<Usage> usages,
                                               @NotNull Collection<UsageNode> visibleNodes,
                                               @NotNull UsageViewImpl usageView,
                                               @NotNull UsageViewPresentation presentation) {
        @NotNull List<UsageNode> data = new ArrayList<UsageNode>();
        int filtered = filtered(usages, usageView);
        if (filtered != 0) {
            data.add(createStringNode(UsageViewBundle.message("usages.were.filtered.out", filtered)));
        }
        data.addAll(visibleNodes);
        if (data.isEmpty()) {
            String progressText = UsageViewManagerImpl.getProgressTitle(presentation);
            data.add(createStringNode(progressText));
        }
        data.sort(USAGE_NODE_COMPARATOR);
        return data;
    }

    private static int calcMaxWidth(JTable table) {
        int colsNum = table.getColumnModel().getColumnCount();

        int totalWidth = 0;
        for (int col = 0; col < colsNum - 1; col++) {
            TableColumn column = table.getColumnModel().getColumn(col);
            int preferred = column.getPreferredWidth();
            int width = Math.max(preferred, columnMaxWidth(table, col));
            totalWidth += width;
            column.setMinWidth(width);
            column.setMaxWidth(width);
            column.setWidth(width);
            column.setPreferredWidth(width);
        }

        totalWidth += columnMaxWidth(table, colsNum - 1);

        return totalWidth;
    }

    private static int columnMaxWidth(@NotNull JTable table, int col) {
        TableColumn column = table.getColumnModel().getColumn(col);
        int width = 0;
        for (int row = 0; row < table.getRowCount(); row++) {
            Component component = table.prepareRenderer(column.getCellRenderer(), row, col);

            int rendererWidth = component.getPreferredSize().width;
            width = Math.max(width, rendererWidth + table.getIntercellSpacing().width);
        }
        return width;
    }

    private int myWidth;

    private void rebuildPopup(@NotNull final UsageViewImpl usageView,
                              @NotNull final List<Usage> usages,
                              @NotNull List<UsageNode> nodes,
                              @NotNull final JTable table,
                              @NotNull final JBPopup popup,
                              @NotNull final UsageViewPresentation presentation,
                              @NotNull final RelativePoint popupPosition,
                              boolean findUsagesInProgress) {
        ApplicationManager.getApplication().assertIsDispatchThread();

        boolean shouldShowMoreSeparator = usages.contains(MORE_USAGES_SEPARATOR);
        if (shouldShowMoreSeparator) {
            nodes.add(MORE_USAGES_SEPARATOR_NODE);
        }

        String title = presentation.getTabText();
        String fullTitle = getFullTitle(usages, title, shouldShowMoreSeparator, nodes.size() - (shouldShowMoreSeparator ? 1 : 0), findUsagesInProgress);

        ((AbstractPopup) popup).setCaption(fullTitle);

        List<UsageNode> data = collectData(usages, nodes, usageView, presentation);
        MyModel tableModel = setTableModel(table, usageView, data);
        List<UsageNode> existingData = tableModel.getItems();

        int row = table.getSelectedRow();

        int newSelection = updateModel(tableModel, existingData, data, row == -1 ? 0 : row);
        if (newSelection < 0 || newSelection >= tableModel.getRowCount()) {
            ScrollingUtil.ensureSelectionExists(table);
            newSelection = table.getSelectedRow();
        } else {
            table.getSelectionModel().setSelectionInterval(newSelection, newSelection);
        }
        ScrollingUtil.ensureIndexIsVisible(table, newSelection, 0);

        setSizeAndDimensions(table, popup, popupPosition, data);
    }

    // returns new selection
    private static int updateModel(@NotNull MyModel tableModel, @NotNull List<UsageNode> listOld, @NotNull List<UsageNode> listNew, int oldSelection) {
        UsageNode[] oa = listOld.toArray(new UsageNode[listOld.size()]);
        UsageNode[] na = listNew.toArray(new UsageNode[listNew.size()]);
        List<ModelDiff.Cmd> cmds = ModelDiff.createDiffCmds(tableModel, oa, na);
        int selection = oldSelection;
        if (cmds != null) {
            for (ModelDiff.Cmd cmd : cmds) {
                selection = cmd.translateSelection(selection);
                cmd.apply();
            }
        }
        return selection;
    }

    private void setSizeAndDimensions(@NotNull JTable table,
                                      @NotNull JBPopup popup,
                                      @NotNull RelativePoint popupPosition,
                                      @NotNull List<UsageNode> data) {
        JComponent content = popup.getContent();
        Window window = SwingUtilities.windowForComponent(content);
        Dimension d = window.getSize();

        int width = calcMaxWidth(table);
        width = (int) Math.max(d.getWidth(), width);
        Dimension headerSize = ((AbstractPopup) popup).getHeaderPreferredSize();
        width = Math.max((int) headerSize.getWidth(), width);
        width = Math.max(myWidth, width);

        if (myWidth == -1) myWidth = width;
        int newWidth = Math.max(width, d.width + width - myWidth);

        myWidth = newWidth;

        int rowsToShow = Math.min(30, data.size());
        Dimension dimension = new Dimension(newWidth, table.getRowHeight() * rowsToShow);
        Rectangle rectangle = fitToScreen(dimension, popupPosition, table);
        dimension = rectangle.getSize();
        Point location = window.getLocation();
        if (!location.equals(rectangle.getLocation())) {
            window.setLocation(rectangle.getLocation());
        }

        if (!data.isEmpty()) {
            ScrollingUtil.ensureSelectionExists(table);
        }
        table.setSize(dimension);
        //table.setPreferredSize(dimension);
        //table.setMaximumSize(dimension);
        //table.setPreferredScrollableViewportSize(dimension);


        Dimension footerSize = ((AbstractPopup) popup).getFooterPreferredSize();

        int newHeight = (int) (dimension.height + headerSize.getHeight() + footerSize.getHeight()) + 4/* invisible borders, margins etc*/;
        Dimension newDim = new Dimension(dimension.width, newHeight);
        window.setSize(newDim);
        window.setMinimumSize(newDim);
        window.setMaximumSize(newDim);

        window.validate();
        window.repaint();
        table.revalidate();
        table.repaint();
    }

    private static Rectangle fitToScreen(@NotNull Dimension newDim, @NotNull RelativePoint popupPosition, JTable table) {
        Rectangle rectangle = new Rectangle(popupPosition.getScreenPoint(), newDim);
        ScreenUtil.fitToScreen(rectangle);
        if (rectangle.getHeight() != newDim.getHeight()) {
            int newHeight = (int) rectangle.getHeight();
            int roundedHeight = newHeight - newHeight % table.getRowHeight();
            rectangle.setSize((int) rectangle.getWidth(), Math.max(roundedHeight, table.getRowHeight()));
        }
        return rectangle;

    }

    private void appendMoreUsages(Editor editor, @NotNull RelativePoint popupPosition, @NotNull FindUsagesHandler handler, int maxUsages) {
        showElementUsages(handler, editor, popupPosition, maxUsages + USAGES_PAGE_SIZE, getDefaultOptions(handler), handler.getPrimaryElements());
    }

    private void addUsageNodes(@NotNull GroupNode root, @NotNull final UsageViewImpl usageView, @NotNull List<UsageNode> outNodes) {
        for (UsageNode node : root.getUsageNodes()) {
            Usage usage = node.getUsage();
            if (usageView.isVisible(usage)) {
                node.setParent(root);
                outNodes.add(node);
            }
        }
        for (GroupNode groupNode : root.getSubGroups()) {
            groupNode.setParent(root);
            addUsageNodes(groupNode, usageView, outNodes);
        }
    }

    @Override
    public void update(AnActionEvent e) {
        FindUsagesInFileAction.updateFindUsagesAction(e);
    }

    private void navigateAndHint(@NotNull Usage usage,
                                 @Nullable final String hint,
                                 @NotNull final FindUsagesHandler handler,
                                 @NotNull final RelativePoint popupPosition,
                                 final int maxUsages,
                                 @NotNull final FindUsagesOptions options) {
        usage.navigate(true);
        if (hint == null) return;
        final Editor newEditor = getEditorFor(usage);
        if (newEditor == null) return;
        final Project project = handler.getProject();
        //opening editor is performing in invokeLater
        IdeFocusManager.getInstance(project).doWhenFocusSettlesDown(new Runnable() {
            @Override
            public void run() {
                newEditor.getScrollingModel().runActionOnScrollingFinished(new Runnable() {
                    @Override
                    public void run() {
                        // after new editor created, some editor resizing events are still bubbling. To prevent hiding hint, invokeLater this
                        IdeFocusManager.getInstance(project).doWhenFocusSettlesDown(new Runnable() {
                            @Override
                            public void run() {
                                if (newEditor.getComponent().isShowing()) {
                                    showHint(hint, newEditor, popupPosition, handler, maxUsages, options);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Nullable
    private static Editor getEditorFor(@NotNull Usage usage) {
        FileEditorLocation location = usage.getLocation();
        FileEditor newFileEditor = location == null ? null : location.getEditor();
        return newFileEditor instanceof TextEditor ? ((TextEditor) newFileEditor).getEditor() : null;
    }

    private static class MyTable extends JTable implements DataProvider {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public Object getData(@NonNls String dataId) {
            if (LangDataKeys.PSI_ELEMENT.is(dataId)) {
                final int[] selected = getSelectedRows();
                if (selected.length == 1) {
                    return getPsiElementForHint(getValueAt(selected[0], 0));
                }
            }
            return null;
        }

        //        @Override
        @Nullable
        PsiElement getPsiElementForHint(Object selectedValue) {
            if (selectedValue instanceof UsageNode) {
                final Usage usage = ((UsageNode) selectedValue).getUsage();
                if (usage instanceof UsageInfo2UsageAdapter) {
                    final PsiElement element = ((UsageInfo2UsageAdapter) usage).getElement();
                    if (element != null) {
                        final PsiElement view = UsageToPsiElementProvider.findAppropriateParentFrom(element);
                        return view == null ? element : view;
                    }
                }
            }
            return null;
        }
    }

    public static class StringNode extends UsageNode {
        private final Object myString;

        StringNode(Object string) {
//            super(NullUsage.INSTANCE, new UsageViewTreeModelBuilder(new UsageViewPresentation(), UsageTarget.EMPTY_ARRAY));
            super(null, NullUsage.INSTANCE);
            myString = string;
        }

        @Override
        public String toString() {
            return myString.toString();
        }
    }

    private static class MySpeedSearch extends SpeedSearchBase<JTable> {
        public MySpeedSearch(@NotNull MyTable table) {
            super(table);
        }

        @Override
        protected int getSelectedIndex() {
            return getTable().getSelectedRow();
        }

        @Override
        protected int convertIndexToModel(int viewIndex) {
            return getTable().convertRowIndexToModel(viewIndex);
        }

        @NotNull
        @Override
        protected Object[] getAllElements() {
            return ((MyModel) getTable().getModel()).getItems().toArray();
        }

        @Override
        protected String getElementText(@NotNull Object element) {
            if (!(element instanceof UsageNode)) return element.toString();
            UsageNode node = (UsageNode) element;
            if (node instanceof StringNode) return "";
            Usage usage = node.getUsage();
            if (usage == MORE_USAGES_SEPARATOR) return "";
            GroupNode group = (GroupNode) node.getParent();
            return usage.getPresentation().getPlainText() + group;
        }

        @Override
        protected void selectElement(Object element, String selectedText) {
            List<UsageNode> data = ((MyModel) getTable().getModel()).getItems();
            int i = data.indexOf(element);
            if (i == -1) return;
            final int viewRow = getTable().convertRowIndexToView(i);
            getTable().getSelectionModel().setSelectionInterval(viewRow, viewRow);
            TableUtil.scrollSelectionToVisible(getTable());
        }

        private MyTable getTable() {
            return (MyTable) myComponent;
        }
    }

}
