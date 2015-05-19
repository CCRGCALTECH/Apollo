package org.bbop.apollo.gwt.client;

import com.google.gwt.cell.client.ClickableTextCell;
import com.google.gwt.cell.client.FieldUpdater;
import com.google.gwt.cell.client.NumberCell;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.builder.shared.DivBuilder;
import com.google.gwt.dom.builder.shared.TableCellBuilder;
import com.google.gwt.dom.builder.shared.TableRowBuilder;
import com.google.gwt.dom.client.BrowserEvents;
import com.google.gwt.dom.client.Style;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.KeyUpEvent;
import com.google.gwt.event.dom.client.KeyUpHandler;
import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.event.logical.shared.SelectionHandler;
import com.google.gwt.http.client.*;
import com.google.gwt.i18n.client.NumberFormat;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.safehtml.shared.SafeHtml;
import com.google.gwt.safehtml.shared.SafeHtmlBuilder;
import com.google.gwt.text.shared.AbstractSafeHtmlRenderer;
import com.google.gwt.text.shared.SafeHtmlRenderer;
import com.google.gwt.uibinder.client.UiBinder;
import com.google.gwt.uibinder.client.UiField;
import com.google.gwt.uibinder.client.UiHandler;
import com.google.gwt.user.cellview.client.*;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.ui.*;
import com.google.gwt.view.client.*;
import org.bbop.apollo.gwt.client.dto.AnnotationInfo;
import org.bbop.apollo.gwt.client.dto.AnnotationInfoConverter;
import org.bbop.apollo.gwt.client.dto.UserInfo;
import org.bbop.apollo.gwt.client.dto.UserInfoConverter;
import org.bbop.apollo.gwt.client.event.*;
import org.bbop.apollo.gwt.client.resources.TableResources;
import org.bbop.apollo.gwt.client.rest.AnnotationRestService;
import org.bbop.apollo.gwt.client.rest.UserRestService;
import org.bbop.apollo.gwt.shared.FeatureStringEnum;
import org.bbop.apollo.gwt.shared.PermissionEnum;
import org.gwtbootstrap3.client.ui.Container;
import org.gwtbootstrap3.client.ui.Label;
import org.gwtbootstrap3.client.ui.ListBox;
import org.gwtbootstrap3.client.ui.TextBox;

import java.util.*;

/**
 * Created by ndunn on 12/17/14.
 */
public class AnnotatorPanel extends Composite {

    interface AnnotatorPanelUiBinder extends UiBinder<com.google.gwt.user.client.ui.Widget, AnnotatorPanel> {
    }

    private static AnnotatorPanelUiBinder ourUiBinder = GWT.create(AnnotatorPanelUiBinder.class);

    private Column<AnnotationInfo, String> nameColumn;
    private TextColumn<AnnotationInfo> typeColumn;
    private TextColumn<AnnotationInfo> sequenceColumn;
    private Column<AnnotationInfo, Number> lengthColumn;
    private Map<Integer, AnnotationInfo> annotationInfoMap = new HashMap<>();
    private long requestIndex = 0;

    @UiField
    TextBox nameSearchBox;
    @UiField(provided = true)
    SuggestBox sequenceList;

    DataGrid.Resources tablecss = GWT.create(TableResources.TableCss.class);
    @UiField(provided = true)
    DataGrid<AnnotationInfo> dataGrid = new DataGrid<>(20, tablecss);
    @UiField(provided = true)
    SimplePager pager = null;


    @UiField
    ListBox typeList;
    @UiField
    static GeneDetailPanel geneDetailPanel;
    @UiField
    static TranscriptDetailPanel transcriptDetailPanel;
    @UiField
    static ExonDetailPanel exonDetailPanel;
    @UiField
    static RepeatRegionDetailPanel repeatRegionDetailPanel;
    @UiField
    static TabLayoutPanel tabPanel;
    @UiField
    ListBox userField;
    //    @UiField
//    ListBox groupField;
//    @UiField
//    Row userFilterRow;
    @UiField
    SplitLayoutPanel splitPanel;
    @UiField
    Container northPanelContainer;
//    @UiField
//    Button cdsButton;
//    @UiField
//    Button stopCodonButton;


    private MultiWordSuggestOracle sequenceOracle = new ReferenceSequenceOracle();

    //    private static ListDataProvider<AnnotationInfo> dataProvider = new ListDataProvider<>();
    private AsyncDataProvider<AnnotationInfo> dataProvider;
    //    private static List<AnnotationInfo> annotationInfoList = new ArrayList<>();
//    private static List<AnnotationInfo> filteredAnnotationList = dataProvider.getList();
    private final Set<String> showingTranscripts = new HashSet<String>();


    public AnnotatorPanel() {
        pager = new SimplePager(SimplePager.TextLocation.CENTER);
        sequenceList = new SuggestBox(sequenceOracle);
        sequenceList.getElement().setAttribute("placeHolder", "All Reference Sequences");
        dataGrid.setWidth("100%");
        dataGrid.setTableBuilder(new CustomTableBuilder());
        dataGrid.setLoadingIndicator(new Label("Loading"));
        dataGrid.setEmptyTableWidget(new Label("No results"));

        exportStaticMethod(this);

        initWidget(ourUiBinder.createAndBindUi(this));

        initializeTable();

        initializeTypes();
        initializeUsers();

        sequenceList.addSelectionHandler(new SelectionHandler<SuggestOracle.Suggestion>() {
            @Override
            public void onSelection(SelectionEvent<SuggestOracle.Suggestion> event) {
                reload();
            }
        });

        sequenceList.addKeyUpHandler(new KeyUpHandler() {
            @Override
            public void onKeyUp(KeyUpEvent event) {
                if (sequenceList.getText() == null || sequenceList.getText().trim().length() == 0) {
                    reload();
                }
            }
        });


        tabPanel.addSelectionHandler(new SelectionHandler<Integer>() {
            @Override
            public void onSelection(SelectionEvent<Integer> event) {
                exonDetailPanel.redrawExonTable();
            }
        });

        Annotator.eventBus.addHandler(AnnotationInfoChangeEvent.TYPE, new AnnotationInfoChangeEventHandler() {
            @Override
            public void onAnnotationChanged(AnnotationInfoChangeEvent annotationInfoChangeEvent) {
                reload();
            }
        });

        Annotator.eventBus.addHandler(UserChangeEvent.TYPE,
                new UserChangeEventHandler() {
                    @Override
                    public void onUserChanged(UserChangeEvent authenticationEvent) {
                        switch (authenticationEvent.getAction()) {
                            case PERMISSION_CHANGED:
                                PermissionEnum hiPermissionEnum = authenticationEvent.getHighestPermission();
                                if (MainPanel.getInstance().isCurrentUserAdmin()) {
                                    hiPermissionEnum = PermissionEnum.ADMINISTRATE;
                                }
                                boolean editable = false;
                                switch (hiPermissionEnum) {
                                    case ADMINISTRATE:
                                    case WRITE:
                                        editable = true;
                                        break;
                                    // default is false
                                }
                                transcriptDetailPanel.setEditable(editable);
                                geneDetailPanel.setEditable(editable);
                                exonDetailPanel.setEditable(editable);
                                repeatRegionDetailPanel.setEditable(editable);
                                reload();
                                break;
                        }
                    }
                }
        );

        // TODO: not sure if this was necessary, leaving it here until it fails
        Annotator.eventBus.addHandler(OrganismChangeEvent.TYPE, new OrganismChangeEventHandler() {
            @Override
            public void onOrganismChanged(OrganismChangeEvent organismChangeEvent) {
                if (organismChangeEvent.getAction() == OrganismChangeEvent.Action.LOADED_ORGANISMS) {
                    sequenceList.setText(organismChangeEvent.getCurrentSequence());
                    reload();
                }
            }
        });


        userField.setVisible(false);
//        groupField.setVisible(false);

        Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
            @Override
            public void execute() {
                if (MainPanel.getInstance().isCurrentUserAdmin()) {
//                    splitPanel.setWidgetSize(northPanelContainer, 150);
                    userField.setVisible(true);
//                    groupField.setVisible(true);
                } else {
                    userField.setVisible(false);
//                    splitPanel.setWidgetSize(northPanelContainer, 100);
                }
            }
        });

    }


    private void initializeUsers() {
        userField.clear();
        userField.addItem("All Users", "");
        RequestCallback requestCallback = new RequestCallback() {
            @Override
            public void onResponseReceived(Request request, Response response) {
                JSONValue returnValue = JSONParser.parseStrict(response.getText());
                JSONArray array = returnValue.isArray();

                for (int i = 0; i < array.size(); i++) {
                    JSONObject object = array.get(i).isObject();
                    UserInfo userInfo = UserInfoConverter.convertToUserInfoFromJSON(object);
                    userField.addItem(userInfo.getName(), userInfo.getEmail());
                }

            }

            @Override
            public void onError(Request request, Throwable exception) {
                Window.alert("Error retrieving users: " + exception.fillInStackTrace());
            }
        };
        UserRestService.loadUsers(requestCallback);
    }

    private void initializeTypes() {
        typeList.addItem("All Types", "");
        typeList.addItem("Gene");
        typeList.addItem("Pseudogene");
        typeList.addItem("tRNA");
        typeList.addItem("snRNA");
        typeList.addItem("snoRNA");
        typeList.addItem("ncRNA");
        typeList.addItem("rRNA");
        typeList.addItem("miRNA");
        typeList.addItem("Transposable Element", "transposable_element");
        typeList.addItem("Repeat Region", "repeat_region");
        // TODO: add rest
    }

    private static void updateAnnotationInfo(AnnotationInfo annotationInfo) {
        String type = annotationInfo.getType();
        GWT.log("annotation type: " + type);
        geneDetailPanel.setVisible(false);
        transcriptDetailPanel.setVisible(false);
        repeatRegionDetailPanel.setVisible(false);
        switch (type) {
            case "gene":
            case "pseudogene":
                geneDetailPanel.updateData(annotationInfo);
                tabPanel.getTabWidget(1).getParent().setVisible(false);
                tabPanel.selectTab(0);
                break;
            case "Transcript":
                transcriptDetailPanel.updateData(annotationInfo);
                tabPanel.getTabWidget(1).getParent().setVisible(true);
                exonDetailPanel.updateData(annotationInfo);
                break;
            case "mRNA":
            case "miRNA":
            case "tRNA":
            case "rRNA":
            case "snRNA":
            case "snoRNA":
            case "ncRNA":
                transcriptDetailPanel.updateData(annotationInfo);
                tabPanel.getTabWidget(1).getParent().setVisible(true);
                exonDetailPanel.updateData(annotationInfo);
//                exonDetailPanel.setVisible(true);
                break;
            case "transposable_element":
            case "repeat_region":
                fireAnnotationInfoChangeEvent(annotationInfo);
                repeatRegionDetailPanel.updateData(annotationInfo);
                tabPanel.getTabWidget(1).getParent().setVisible(false);
                break;
//            case "exon":
//                exonDetailPanel.updateData(annotationInfo);
//                break;
//            case "CDS":
//                cdsDetailPanel.updateDetailData(AnnotationRestService.convertAnnotationInfoToJSONObject(annotationInfo));
//                break;
            default:
                GWT.log("not sure what to do with " + type);
        }
    }

    public static void fireAnnotationInfoChangeEvent(AnnotationInfo annotationInfo) {
        // this method is for firing AnnotationInfoChangeEvent for single level features such as transposable_element and repeat_region
        AnnotationInfoChangeEvent annotationInfoChangeEvent = new AnnotationInfoChangeEvent(annotationInfo, AnnotationInfoChangeEvent.Action.SET_FOCUS);
        Annotator.eventBus.fireEvent(annotationInfoChangeEvent);
    }


    private void initializeTable() {
        // View friends.
        SafeHtmlRenderer<String> anchorRenderer = new AbstractSafeHtmlRenderer<String>() {
            @Override
            public SafeHtml render(String object) {
                SafeHtmlBuilder sb = new SafeHtmlBuilder();
                sb.appendHtmlConstant("<a href=\"javascript:;\">").appendEscaped(object)
                        .appendHtmlConstant("</a>");
                return sb.toSafeHtml();
            }
        };


        nameColumn = new Column<AnnotationInfo, String>(new ClickableTextCell(anchorRenderer)) {
            @Override
            public String getValue(AnnotationInfo annotationInfo) {
                return annotationInfo.getName();
            }
        };

        nameColumn.setFieldUpdater(new FieldUpdater<AnnotationInfo, String>() {
            @Override
            public void update(int index, AnnotationInfo annotationInfo, String value) {
                if (showingTranscripts.contains(annotationInfo.getUniqueName())) {
                    showingTranscripts.remove(annotationInfo.getUniqueName());
                } else {
                    showingTranscripts.add(annotationInfo.getUniqueName());
                }

                // Redraw the modified row.
                dataGrid.redrawRow(index);
            }
        });
        nameColumn.setSortable(true);

        sequenceColumn = new TextColumn<AnnotationInfo>() {
            @Override
            public String getValue(AnnotationInfo annotationInfo) {
                return annotationInfo.getSequence();
//                return "cats";
            }
        };
        sequenceColumn.setSortable(true);
        sequenceColumn.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);

        typeColumn = new TextColumn<AnnotationInfo>() {
            @Override
            public String getValue(AnnotationInfo annotationInfo) {

                String type = annotationInfo.getType();
                switch (type) {
                    case "repeat_region":
                        return "repeat reg";
                    case "transposable_element":
                        return "transp elem";
                    default:
                        return type;
                }
            }
        };
        typeColumn.setSortable(true);
        typeColumn.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_CENTER);

        lengthColumn = new Column<AnnotationInfo, Number>(new NumberCell()) {
            @Override
            public Integer getValue(AnnotationInfo annotationInfo) {
                return annotationInfo.getLength();
            }
        };
        lengthColumn.setSortable(true);
        lengthColumn.setHorizontalAlignment(HasHorizontalAlignment.ALIGN_RIGHT);
        lengthColumn.setCellStyleNames("dataGridLastColumn");


//        dataGrid.addColumn(nameColumn, SafeHtmlUtils.fromSafeConstant("<br/>"));
        dataGrid.addColumn(nameColumn, "Name");
        dataGrid.addColumn(sequenceColumn, "Seq");
        dataGrid.addColumn(typeColumn, "Type");
        dataGrid.addColumn(lengthColumn, "Length");
//        dataGrid.addColumn(filterColumn, "Warnings");

        dataGrid.setColumnWidth(0, "55%");
        dataGrid.setColumnWidth(1, "15%");
        dataGrid.setColumnWidth(2, "15%");
        dataGrid.setColumnWidth(3, "15%");


//        ColumnSortEvent.ListHandler<AnnotationInfo> sortHandler = new ColumnSortEvent.ListHandler<AnnotationInfo>(filteredAnnotationList);
//        dataGrid.addColumnSortHandler(sortHandler);
//
//        // Specify a custom table.
////        dataGrid.setTableBuilder(new AnnotationInfoTableBuilder(dataGrid,sortHandler,showingTranscripts));
//
//        sortHandler.setComparator(nameColumn, new Comparator<AnnotationInfo>() {
//            @Override
//            public int compare(AnnotationInfo o1, AnnotationInfo o2) {
//                return o1.getName().compareToIgnoreCase(o2.getName());
//            }
//        });
//
//        sortHandler.setComparator(sequenceColumn, new Comparator<AnnotationInfo>() {
//            @Override
//            public int compare(AnnotationInfo o1, AnnotationInfo o2) {
//                return o1.getSequence().compareToIgnoreCase(o2.getSequence());
//            }
//        });
//
//
//        sortHandler.setComparator(typeColumn, new Comparator<AnnotationInfo>() {
//            @Override
//            public int compare(AnnotationInfo o1, AnnotationInfo o2) {
//                return o1.getType().compareToIgnoreCase(o2.getType());
//            }
//        });
//
//        sortHandler.setComparator(lengthColumn, new Comparator<AnnotationInfo>() {
//            @Override
//            public int compare(AnnotationInfo o1, AnnotationInfo o2) {
//                return o1.getLength() - o2.getLength();
//            }
//        });

        dataProvider = new AsyncDataProvider<AnnotationInfo>() {
            @Override
            protected void onRangeChanged(HasData<AnnotationInfo> display) {
                final Range range = display.getVisibleRange();
                final ColumnSortList sortList = dataGrid.getColumnSortList();
                final int start = range.getStart();
                final int length = range.getLength();


                RequestCallback requestCallback = new RequestCallback() {
                    @Override
                    public void onResponseReceived(Request request, Response response) {
                        JSONValue returnValue = JSONParser.parseStrict(response.getText());
                        long localRequestValue = (long) returnValue.isObject().get(FeatureStringEnum.REQUEST_INDEX.getValue()).isNumber().doubleValue();
                        // returns
                        if (localRequestValue <= requestIndex) {
                            return;
                        } else {
                            requestIndex = localRequestValue;
                        }

                        JSONArray jsonArray = returnValue.isObject().get(FeatureStringEnum.FEATURES.getValue()).isArray();
                        List<AnnotationInfo> annotationInfoList = AnnotationInfoConverter.convertFromJsonArray(jsonArray);
                        annotationInfoMap.clear();
                        for (int i = 0; i < annotationInfoList.size(); i++) {
                            annotationInfoMap.put(i, annotationInfoList.get(i));
                        }
                        dataGrid.setRowData(start, annotationInfoList);
                    }

                    @Override
                    public void onError(Request request, Throwable exception) {
                        Window.alert("error getting annotation info: " + exception);
                    }
                };


                ColumnSortList.ColumnSortInfo nameSortInfo = sortList.get(0);
//                if (nameSortInfo.getColumn().isSortable()) {
                Column<AnnotationInfo, ?> sortColumn = (Column<AnnotationInfo, ?>) sortList.get(0).getColumn();
                Integer columnIndex = dataGrid.getColumnIndex(sortColumn);
                String searchColumnString = columnIndex == 0 ? "name" : "length";
                Boolean sortNameAscending = nameSortInfo.isAscending();


                AnnotationRestService.getAnnotations(requestCallback, sequenceList.getText(), nameSearchBox.getText(), typeList.getSelectedValue(), userField.getSelectedValue(), start, length, searchColumnString, sortNameAscending);
//                }
            }
        };

        ColumnSortEvent.AsyncHandler columnSortHandler = new ColumnSortEvent.AsyncHandler(dataGrid);
        dataGrid.addColumnSortHandler(columnSortHandler);
        dataGrid.getColumnSortList().push(nameColumn);
        dataGrid.getColumnSortList().push(lengthColumn);

        dataProvider.addDataDisplay(dataGrid);
        pager.setDisplay(dataGrid);

        dataGrid.addCellPreviewHandler(new CellPreviewEvent.Handler<AnnotationInfo>() {
            @Override
            public void onCellPreview(CellPreviewEvent<AnnotationInfo> event) {
                AnnotationInfo annotationInfo = event.getValue();
                if (event.getNativeEvent().getType().equals(BrowserEvents.CLICK)) {
                    if (event.getContext().getSubIndex() == 0) {
                        // subIndex from dataGrid will be 0 only when top-level cell values are clicked
                        // ie. gene, pseudogene
                        GWT.log("Safe to call updateAnnotationInfo");
                        updateAnnotationInfo(annotationInfo);
                    }
                }
            }
        });

    }

    private String getType(JSONObject internalData) {
        return internalData.get("type").isObject().get("name").isString().stringValue();
    }


    public void reload() {
        refreshQuery();
//        loadOrganismAndSequence(sequenceList.getText());
    }

//    private void filterList() {
//        filteredAnnotationList.clear();
//        for (int i = 0; i < annotationInfoList.size(); i++) {
//            AnnotationInfo annotationInfo = annotationInfoList.get(i);
//            if (searchMatches(annotationInfo)) {
//                filteredAnnotationList.add(annotationInfo);
//            } else {
//                if (searchMatches(annotationInfo.getAnnotationInfoSet())) {
//                    filteredAnnotationList.add(annotationInfo);
//                }
//            }
//        }
//    }

    private boolean searchMatches(Set<AnnotationInfo> annotationInfoSet) {
        for (AnnotationInfo annotationInfo : annotationInfoSet) {
            if (searchMatches(annotationInfo)) {
                return true;
            }
        }
        return false;
    }

    private boolean searchMatches(AnnotationInfo annotationInfo) {
        String nameText = nameSearchBox.getText();
        String typeText = typeList.getSelectedValue();
        String userText = userField.getSelectedValue();
        return (
                (annotationInfo.getName().toLowerCase().contains(nameText.toLowerCase()))
                        &&
                        annotationInfo.getType().toLowerCase().contains(typeText.toLowerCase())
                        &&
                        annotationInfo.getOwner().toLowerCase().contains(userText)
        );

    }

    private AnnotationInfo generateAnnotationInfo(JSONObject object) {
        return generateAnnotationInfo(object, true);
    }

    private AnnotationInfo generateAnnotationInfo(JSONObject object, boolean processChildren) {
        AnnotationInfo annotationInfo = new AnnotationInfo();
        annotationInfo.setName(object.get("name").isString().stringValue());
        annotationInfo.setType(object.get("type").isObject().get("name").isString().stringValue());
        if (object.get("symbol") != null) {
            annotationInfo.setSymbol(object.get("symbol").isString().stringValue());
        }
        if (object.get("description") != null) {
            annotationInfo.setDescription(object.get("description").isString().stringValue());
        }
        annotationInfo.setMin((int) object.get("location").isObject().get("fmin").isNumber().doubleValue());
        annotationInfo.setMax((int) object.get("location").isObject().get("fmax").isNumber().doubleValue());
        annotationInfo.setStrand((int) object.get("location").isObject().get("strand").isNumber().doubleValue());
        annotationInfo.setUniqueName(object.get("uniquename").isString().stringValue());
        annotationInfo.setSequence(object.get("sequence").isString().stringValue());
        if (object.get("owner") != null) {
            annotationInfo.setOwner(object.get("owner").isString().stringValue());
        }

        List<String> noteList = new ArrayList<>();
        if (object.get("notes") != null) {
            JSONArray jsonArray = object.get("notes").isArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                String note = jsonArray.get(i).isString().stringValue();
                noteList.add(note);
            }
        }
        annotationInfo.setNoteList(noteList);

        if (processChildren && object.get("children") != null) {
            JSONArray jsonArray = object.get("children").isArray();
            for (int i = 0; i < jsonArray.size(); i++) {
                AnnotationInfo childAnnotation = generateAnnotationInfo(jsonArray.get(i).isObject(), true);
                annotationInfo.addChildAnnotation(childAnnotation);
            }
        }

        return annotationInfo;
    }

    public void refreshQuery() {
        dataGrid.setVisibleRangeAndClearData(dataGrid.getVisibleRange(), true);
    }

    @UiHandler(value = {"typeList", "userField"})
    public void searchType(ChangeEvent changeEvent) {
        refreshQuery();
    }

    @UiHandler("nameSearchBox")
    public void searchName(KeyUpEvent keyUpEvent) {
        refreshQuery();
    }


//    @UiHandler("sequenceList")
//    public void changeRefSequence(SelectionEvent changeEvent) {
////        selectedSequenceName = sequenceList.getText();
//        reload();
//    }

//    @UiHandler("sequenceList")
//    public void changeRefSequence(KeyUpEvent changeEvent) {
////        selectedSequenceName = sequenceList.getText();
//        reload();
//    }


    // TODO: need to cache these or retrieve from the backend
    public void displayTranscript(int geneIndex, String uniqueName) {
        // 1 - get the correct gene
        AnnotationInfo annotationInfo = annotationInfoMap.get(geneIndex);
        AnnotationInfoChangeEvent annotationInfoChangeEvent = new AnnotationInfoChangeEvent(annotationInfo, AnnotationInfoChangeEvent.Action.SET_FOCUS);

        for (AnnotationInfo childAnnotation : annotationInfo.getAnnotationInfoSet()) {
            if (childAnnotation.getUniqueName().equalsIgnoreCase(uniqueName)) {
                exonDetailPanel.updateData(childAnnotation);
                updateAnnotationInfo(childAnnotation);
                Annotator.eventBus.fireEvent(annotationInfoChangeEvent);
                return;
            }
        }
    }

    public static native void exportStaticMethod(AnnotatorPanel annotatorPanel) /*-{
        $wnd.displayTranscript = $entry(this.@org.bbop.apollo.gwt.client.AnnotatorPanel::displayTranscript(ILjava/lang/String;));
    }-*/;

    private class CustomTableBuilder extends AbstractCellTableBuilder<AnnotationInfo> {

        public CustomTableBuilder() {
            super(dataGrid);
        }


        @Override
        protected void buildRowImpl(AnnotationInfo rowValue, int absRowIndex) {
            buildAnnotationRow(rowValue, absRowIndex, false);

            if (showingTranscripts.contains(rowValue.getUniqueName())) {
                // add some random rows
                Set<AnnotationInfo> annotationInfoSet = rowValue.getAnnotationInfoSet();
                if (annotationInfoSet.size() > 0) {
                    for (AnnotationInfo annotationInfo : annotationInfoSet) {
                        buildAnnotationRow(annotationInfo, absRowIndex, true);
                    }
                }
            }
        }

        private void buildAnnotationRow(final AnnotationInfo rowValue, int absRowIndex, boolean showTranscripts) {

            TableRowBuilder row = startRow();
            TableCellBuilder td = row.startTD();

            td.style().outlineStyle(Style.OutlineStyle.NONE).endStyle();
            if (showTranscripts) {
                // TODO: this is ugly, but it works
                // a custom cell rendering might work as well, but not sure

                String transcriptStyle = "margin-left: 10px; color: green; padding-left: 5px; padding-right: 5px; border-radius: 15px; background-color: #EEEEEE;";
                HTML html = new HTML("<a style='" + transcriptStyle + "' onclick=\"displayTranscript(" + absRowIndex + ",'" + rowValue.getUniqueName() + "');\">" + rowValue.getName() + "</a>");
                SafeHtml htmlString = new SafeHtmlBuilder().appendHtmlConstant(html.getHTML()).toSafeHtml();
                td.html(htmlString);
            } else {
                renderCell(td, createContext(0), nameColumn, rowValue);
            }
            td.endTD();

            // Sequence column.
            td = row.startTD();
//            td.className(cellStyles);
            td.style().outlineStyle(Style.OutlineStyle.NONE).endStyle();
            if (showTranscripts) {
                DivBuilder div = td.startDiv();
                div.style().trustedColor("green").endStyle();
//                div.text(rowValue.getSequence());
                td.endDiv();
            } else {
                renderCell(td, createContext(1), sequenceColumn, rowValue);
            }
            td.endTD();

            // Type column.
            td = row.startTD();
//            td.className(cellStyles);
            td.style().outlineStyle(Style.OutlineStyle.NONE).endStyle();
            if (showTranscripts) {
                DivBuilder div = td.startDiv();
                div.style().trustedColor("green").endStyle();
                div.text(rowValue.getType());
                td.endDiv();
            } else {
                renderCell(td, createContext(1), typeColumn, rowValue);
            }
            td.endTD();


            // Length column.
            td = row.startTD();
            td.style().outlineStyle(Style.OutlineStyle.NONE).endStyle();
            if (showTranscripts) {
                DivBuilder div = td.startDiv();
                div.style().trustedColor("green").endStyle();
                div.text(NumberFormat.getDecimalFormat().format(rowValue.getLength()));
                td.endDiv();
                td.endTD();

            } else {
                td.text(NumberFormat.getDecimalFormat().format(rowValue.getLength())).endTD();
            }

            td = row.startTD();
            td.style().outlineStyle(Style.OutlineStyle.NONE).endStyle();

            // TODO: is it necessary to have two separte ones?
//            if(showTranscripts){
            DivBuilder div = td.startDiv();
            SafeHtmlBuilder safeHtmlBuilder = new SafeHtmlBuilder();

            for (String error : rowValue.getNoteList()) {
                safeHtmlBuilder.appendHtmlConstant("<div class='label label-warning'>" + error + "</div>");
            }


            div.html(safeHtmlBuilder.toSafeHtml());
            td.endDiv();
            td.endTD();

            row.endTR();

        }


    }
}