/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cyclop.web.panels.queryeditor.verticalresult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.wicket.Component;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.navigation.paging.IPageableItems;
import org.apache.wicket.markup.repeater.Item;
import org.apache.wicket.model.IModel;
import org.cyclop.model.CqlExtendedColumnName;
import org.cyclop.model.CqlPartitionKey;
import org.cyclop.model.CqlQueryResult;
import org.cyclop.model.CqlRowMetadata;
import org.cyclop.web.components.iterablegrid.IterableGridView;
import org.cyclop.web.panels.queryeditor.QueryResultPanel;

import com.datastax.driver.core.Row;

/** @author Maciej Miklas */
public class QueryResultVerticalPanel extends QueryResultPanel {

    protected final RowsModel rowsModel;

    public QueryResultVerticalPanel(String id, IModel<CqlQueryResult> model) {
	super(id, model);
	rowsModel = new RowsModel();
    }

    @Override
    protected void onInitialize() {
	super.onInitialize();
	initColumnList();
    }

    @Override
    protected void hideResultsTable() {
	super.hideResultsTable();
	rowsModel.getObject().clear();
    }

    private void initColumnList() {
	ListView<CqlExtendedColumnName> columnList = new ListView<CqlExtendedColumnName>(
		"columnList",
		columnsModel) {
	    @Override
	    protected void populateItem(ListItem<CqlExtendedColumnName> item) {
		final CqlExtendedColumnName columnName = item.getModelObject();

		WebMarkupContainer columnListRow = new WebMarkupContainer("columnListRow");
		item.add(columnListRow);

		Label columnNameLabel = new Label("columnName", columnName.part);
		columnListRow.add(columnNameLabel);

		ColumnsModel model = (ColumnsModel) getModel();
		CqlRowMetadata result = model.getResult();
		final CqlPartitionKey partitionKey = result == null ? null : result.partitionKey;

		ListView<Row> columnValueList = new ListView<Row>("columnValueList", rowsModel) {

		    @Override
		    protected void populateItem(ListItem<Row> item) {
			Row row = item.getModelObject();

			Component component = widgetFactory.createForColumn(
				row,
				partitionKey,
				columnName,
				"columnValue");
			item.add(component);
			component.setRenderBodyOnly(true);
		    }
		};
		columnListRow.add(columnValueList);
	    }
	};
	resultTable.add(columnList);
    }

    @Override
    protected IPageableItems initPagingProvider() {

	final List<Row> displayedRows = new ArrayList<>();
	IterableGridView<Row> rowNamesList = new IterableGridView<Row>("rowNamesList", rowDataProvider) {

	    @Override
	    protected void onBeforeRender() {
		displayedRows.clear();
		super.onBeforeRender();
	    }

	    @Override
	    protected void populateEmptyItem(Item<Row> item) {
		item.add(new Label("rowName", EMPTYVAL));
	    }

	    @Override
	    protected void populateItem(Item<Row> item) {
		Row row = item.getModel().getObject();
		displayedRows.add(row);
		CqlPartitionKey partitionKey = queryResultModel.getObject().rowMetadata.partitionKey;

		Component component;
		if (partitionKey != null) {
		    component = widgetFactory.createForColumn(row, partitionKey, partitionKey, "rowName");
		}
		else {
		    component = new Label("rowName", displayedRows.size());
		}

		item.add(component);
	    }
	};
	resultTable.add(rowNamesList);
	rowNamesList.setColumns(1);

	rowsModel.setObject(displayedRows);
	return rowNamesList;
    }

    private final static class RowsModel implements IModel<List<Row>> {

	private List<Row> content;

	public RowsModel() {
	    this.content = Collections.emptyList();
	}

	@Override
	public void detach() {
	}

	@Override
	public List<Row> getObject() {
	    return content;
	}

	@Override
	public void setObject(List<Row> object) {
	    content = object;
	}
    }
}
