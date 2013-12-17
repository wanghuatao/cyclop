package org.cyclop.service.cassandra;

import com.datastax.driver.core.ColumnDefinitions;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import org.apache.commons.lang.StringUtils;
import org.cyclop.common.AppConfig;
import org.cyclop.service.model.CqlColumnName;
import org.cyclop.service.model.CqlColumnType;
import org.cyclop.service.model.CqlExtendedColumnName;
import org.cyclop.service.model.CqlKeySpace;
import org.cyclop.service.model.CqlPartitionKey;
import org.cyclop.service.model.CqlQuery;
import org.cyclop.service.model.CqlQueryType;
import org.cyclop.service.model.CqlRow;
import org.cyclop.service.model.CqlSelectResult;
import org.cyclop.service.model.CqlTable;
import org.cyclop.service.model.QueryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.cyclop.common.QueryHelper.KW_SELECT;
import static org.cyclop.common.QueryHelper.extractSpace;
import static org.cyclop.common.QueryHelper.extractTableName;

/**
 * @author Maciej Miklas
 */
@Named
@CassandraVersionQualifier(CassandraVersion.VER_2_x)
class DefaultQueryService implements QueryService {

    private final static Logger LOG = LoggerFactory.getLogger(DefaultQueryService.class);

    @Inject
    protected AppConfig config;

    @Inject
    protected CassandraSession session;

    @Override
    public boolean checkTableExists(CqlTable table) {
        if (table == null) {
            return false;
        }
        StringBuilder cql = new StringBuilder("select columnfamily_name from system.schema_columnfamilies");
        cql.append(" where columnfamily_name='").append(table.partLc).append("' allow filtering");

        ResultSet result = executeSilent(cql.toString());
        boolean tableExists = result != null && !result.isExhausted();
        return tableExists;
    }

    @Override
    public boolean checkKeyspaceExists(CqlKeySpace keySpace) {
        return false;
    }

    @Override
    public ImmutableSortedSet<CqlKeySpace> findAllKeySpaces() {
        ResultSet result = executeSilent("select keyspace_name from system.schema_keyspaces");

        if (result == null) {
            LOG.debug("Cannot read keyspace info");
            return ImmutableSortedSet.of();
        }

        ImmutableSortedSet.Builder<CqlKeySpace> keyspaces = ImmutableSortedSet.naturalOrder();
        for (Row row : result) {
            keyspaces.add(new CqlKeySpace(row.getString("keyspace_name")));
        }
        return keyspaces.build();
    }

    @Override
    public ImmutableSortedSet<CqlTable> findTableNames(CqlKeySpace keySpace) {
        StringBuilder cql = new StringBuilder("select columnfamily_name from system.schema_columnfamilies");
        if (keySpace != null) {
            cql.append(" where keyspace_name='").append(keySpace.partLc).append("'");
        }
        ResultSet result = executeSilent(cql.toString());
        if (result == null) {
            LOG.debug("No table names found for keyspace: " + keySpace);
            return ImmutableSortedSet.of();
        }

        ImmutableSortedSet.Builder<CqlTable> tables = ImmutableSortedSet.naturalOrder();
        for (Row row : result) {
            String columnFamily = row.getString("columnfamily_name");
            columnFamily = StringUtils.trimToNull(columnFamily);
            if (columnFamily == null) {
                continue;
            }
            CqlTable table = new CqlTable(columnFamily);
            tables.add(table);
        }
        return tables.build();
    }

    @Override
    public ImmutableSortedSet<CqlTable> findTableNamesForActiveKeySpace() {
        CqlKeySpace space = session.getActiveKeySpace();
        return findTableNames(space);
    }

    private void setSpace(CqlQuery query) {
        CqlKeySpace space = extractSpace(query);
        session.setActiveKeySpace(space);
    }

    @Override
    public CqlSelectResult execute(CqlQuery query) {
        LOG.debug("Executing CQL: {}", query);

        if (query.type == CqlQueryType.USE) {
            setSpace(query);
        }

        ResultSet cqlResult = execute(query.cql);
        if (cqlResult == null || cqlResult.isExhausted()) {
            return new CqlSelectResult();
        }

        Map<String, CqlColumnType> typeMap = createTypeMap(query);
        // go over rows:
        // - count columns to find those that are the same over many rows
        // - collect rows, since we cannot iterate over results again
        Map<CqlExtendedColumnName, MutableInt> columnsCount = new LinkedHashMap<>();
        ImmutableList.Builder<CqlRow> rowsBuild = ImmutableList.builder();
        CqlPartitionKey partitionKey = null;
        for (Row row : cqlResult) {
            ImmutableList.Builder<CqlExtendedColumnName> colsBuild = ImmutableList.builder();

            CqlPartitionKey partitionKeyTmp = collectAndCountColumns(row, columnsCount, colsBuild, typeMap);
            if (partitionKeyTmp != null) {
                partitionKey = partitionKeyTmp;
            }
            ImmutableList<CqlExtendedColumnName> cols = colsBuild.build();
            CqlRow cqlRow = new CqlRow(row, cols);
            rowsBuild.add(cqlRow);
        }

        // create query result
        ImmutableList<CqlRow> rows = rowsBuild.build();
        if (rows.isEmpty()) {
            return new CqlSelectResult();
        }

        ImmutableList.Builder<CqlExtendedColumnName> commonColumnsBuild = ImmutableList.builder();
        ImmutableList.Builder<CqlExtendedColumnName> dynamicColumnsBuild = ImmutableList.builder();
        for (Map.Entry<CqlExtendedColumnName, MutableInt> entry : columnsCount.entrySet()) {
            if (entry.getValue().anInt > 1) {
                commonColumnsBuild.add(entry.getKey());
            } else {
                dynamicColumnsBuild.add(entry.getKey());
            }
        }
        ImmutableList<CqlExtendedColumnName> commonCols = commonColumnsBuild.build();
        ImmutableList<CqlExtendedColumnName> dynamicColumns = dynamicColumnsBuild.build();
        CqlSelectResult result = new CqlSelectResult(commonCols, dynamicColumns, rows, partitionKey);
        return result;
    }

    private CqlPartitionKey collectAndCountColumns(Row row, Map<CqlExtendedColumnName, MutableInt> columnsCount,
            ImmutableList.Builder<CqlExtendedColumnName> cols, Map<String, CqlColumnType> typeMap) {

        // collect and count all columns
        ColumnDefinitions definitions = row.getColumnDefinitions();
        CqlPartitionKey partitionKey = null;
        for (int colIndex = 0; colIndex < definitions.size(); colIndex++) {
            if (row.isNull(colIndex)) {
                continue;
            }
            DataType dataType = definitions.getType(colIndex);
            String columnNameText = definitions.getName(colIndex);
            CqlColumnType columnType = typeMap.get(columnNameText.toLowerCase());
            if (columnType == null) {
                columnType = CqlColumnType.REGULAR;
                LOG.debug("Column type not found for: {} - using regular", columnNameText);
            }
            CqlExtendedColumnName columnName = new CqlExtendedColumnName(columnType, dataType, columnNameText);
            if (columnType == CqlColumnType.PARTITION_KEY) {
                partitionKey = CqlPartitionKey.fromColumn(columnName);
            }
            cols.add(columnName);
            MutableInt colCount = columnsCount.get(columnName);
            if (colCount == null) {
                columnsCount.put(columnName, new MutableInt());
            } else {
                colCount.anInt++;
            }
        }
        return partitionKey;
    }

    protected ImmutableMap<String, CqlColumnType> createTypeMap(CqlQuery query) {

        CqlTable table = extractTableName(KW_SELECT, query);
        if (table == null) {
            LOG.warn("Could not extract table name from: {}. Column type information is not available.");
            return ImmutableMap.of();
        }

        ResultSet result = executeSilent("select column_name, type from system.schema_columns where " + "columnfamily_name='"
                + table.part + "' allow filtering");
        if (result == null) {
            LOG.warn("Could not read types for columns of table: " + table);
            return ImmutableMap.of();
        }

        ImmutableMap.Builder<String, CqlColumnType> typesBuild = ImmutableMap.builder();
        for (Row row : result) {
            String typeText = StringUtils.trimToNull(row.getString("type"));
            String name = StringUtils.trimToNull(row.getString("column_name"));
            if (typeText == null || name == null) {
                continue;
            }
            CqlColumnType type = extractType(typeText);
            typesBuild.put(name.toLowerCase(), type);
        }
        return typesBuild.build();
    }

    @Override
    public ImmutableSortedSet<CqlColumnName> findColumnNames(CqlTable table) {
        StringBuilder buf = new StringBuilder("select column_name from system.schema_columns");
        if (table != null) {
            buf.append(" where columnfamily_name='");
            buf.append(table.partLc);
            buf.append("'");
        }
        buf.append(" limit ");
        buf.append(config.cassandra.resultLimit);
        buf.append(" allow filtering");

        ResultSet result = executeSilent(buf.toString());
        if (result == null) {
            LOG.warn("Cannot read column names");
            return ImmutableSortedSet.of();
        }

        ImmutableSortedSet.Builder<CqlColumnName> cqlColumnNames = ImmutableSortedSet.naturalOrder();
        for (Row row : result) {
            String name = StringUtils.trimToNull(row.getString("column_name"));
            if (name == null) {
                continue;
            }
            cqlColumnNames.add(new CqlColumnName(DataType.text(), name));
        }

        loadPartitionKeyNames(table, cqlColumnNames);

        return cqlColumnNames.build();
    }

    // required only for cassandra 1.x
    protected void loadPartitionKeyNames(CqlTable table, ImmutableSortedSet.Builder<CqlColumnName> cqlColumnNames) {
    }

    protected CqlColumnType extractType(String typeText) {
        CqlColumnType type;
        try {
            type = CqlColumnType.valueOf(typeText.toUpperCase());
        } catch (IllegalArgumentException ia) {
            LOG.warn("Read unsupported column type: {}", typeText, ia);
            type = CqlColumnType.REGULAR;
        }

        return type;
    }

    @Override
    public ImmutableSortedSet<CqlColumnName> findAllColumnNames() {
        return findColumnNames(null);
    }

    private class MutableInt {
        public int anInt = 1;

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("anInt", anInt).toString();
        }
    }

    protected ResultSet executeSilent(String cql) {
        LOG.debug("Executing: {}", cql);
        ResultSet resultSet = null;
        try {
            resultSet = session.getSession().execute(cql);
        } catch (Exception e) {
            LOG.warn("Error executing CQL: '" + cql + "', reason: " + e.getMessage());
            LOG.debug(e.getMessage(), e);
        }
        return resultSet;
    }

    protected ResultSet execute(String cql) {
        LOG.debug("Executing: {} ", cql);
        ResultSet resultSet = null;
        try {
            resultSet = session.getSession().execute(cql);
        } catch (Exception e) {
            throw new QueryException("Error executing CQL: '" + cql + "', reason: " + e.getMessage(), e);
        }
        return resultSet;
    }

}
