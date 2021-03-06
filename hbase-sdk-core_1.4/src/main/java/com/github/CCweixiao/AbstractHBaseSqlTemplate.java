package com.github.CCweixiao;

import com.github.CCweixiao.client.config.DefaultHBaseSQLRuntimeSetting;
import com.github.CCweixiao.exception.HBaseOperationsException;
import com.github.CCwexiao.dsl.client.HBaseCellResult;
import com.github.CCwexiao.dsl.client.QueryExtInfo;
import com.github.CCwexiao.dsl.client.RowKey;
import com.github.CCwexiao.dsl.client.rowkey.handler.RowKeyHandler;
import com.github.CCwexiao.dsl.config.HBaseColumnSchema;
import com.github.CCwexiao.dsl.config.HBaseSQLRuntimeSetting;
import com.github.CCwexiao.dsl.config.HBaseTableConfig;
import com.github.CCwexiao.dsl.type.TypeHandler;
import com.github.CCwexiao.dsl.util.Util;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.util.Bytes;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * @author leojie 2020/11/28 8:34 下午
 */
public abstract class AbstractHBaseSqlTemplate extends AbstractHBaseConfig implements HBaseSqlOperations, HBaseTableConfigAware, HBaseSQLRuntimeSettingAware {
    protected HBaseTableConfig hBaseTableConfig;
    protected HBaseSQLRuntimeSetting runtimeSetting = new DefaultHBaseSQLRuntimeSetting();

    public AbstractHBaseSqlTemplate(Configuration configuration) {
        super(configuration);
    }

    public AbstractHBaseSqlTemplate(String zkHost, String zkPort) {
        super(zkHost, zkPort);
    }

    public AbstractHBaseSqlTemplate(Properties properties) {
        super(properties);
    }

    protected byte[] tableNameBytes() {
        String tableName = hBaseTableConfig.gethBaseTableSchema().getTableName();
        return Bytes.toBytes(tableName);
    }

    protected HBaseColumnSchema columnSchema(String family, String qualifier) {
        return hBaseTableConfig.gethBaseTableSchema().findColumnSchema(family, qualifier);
    }

    protected int getScanCaching() {
        return runtimeSetting.getScanCachingSize();
    }

    protected int getDeleteBatch() {
        return runtimeSetting.getDeleteBatchSize();
    }

    protected Scan constructScan(RowKey startRowKey, RowKey endRowKey,
                                 Filter filter, QueryExtInfo queryExtInfo) {
        Util.checkRowKey(startRowKey);
        Util.checkRowKey(endRowKey);

        Scan scan = new Scan();
        scan.withStartRow(startRowKey.toBytes());
        scan.withStopRow(endRowKey.toBytes());

        int cachingSize = getScanCaching();

        if (runtimeSetting.isIntelligentScanSize()) {
            if (queryExtInfo != null && queryExtInfo.isLimitSet()) {
                long limitScanSize = queryExtInfo.getStartIndex()
                        + queryExtInfo.getLength();
                if (limitScanSize > Integer.MAX_VALUE) {
                    cachingSize = Integer.MAX_VALUE;
                } else {
                    cachingSize = (int) limitScanSize;
                }
            }
        }

        scan.setCaching(cachingSize);

        scan.setFilter(filter);

        return postConstructScan(scan);
    }

    protected Scan postConstructScan(Scan scan) {
        return scan;
    }

    protected Get constructGet(RowKey rowkey, Filter filter) {
        Util.checkRowKey(rowkey);

        Get get = new Get(rowkey.toBytes());
        get.setFilter(filter);
        return postConstructGet(get);
    }

    protected Get postConstructGet(Get get) {
        return get;
    }


    /**
     * 输入hsql 查询数据，返回HBaseCellResult结合
     *
     * @param hsql hsql
     * @return 数据集合
     */
    public abstract List<List<HBaseCellResult>> select(String hsql);

    protected List<HBaseCellResult> convertToHBaseCellResultList(Result result) {
        final Cell[] cells = result.rawCells();
        if (cells == null || cells.length == 0) {
            return new ArrayList<>();
        }
        String familyStr = null;
        String qualifierStr = null;
        RowKeyHandler rowKeyHandler = null;

        try {


            List<HBaseCellResult> resultList = new ArrayList<>();

            for (Cell cell : cells) {
                familyStr = Bytes.toString(CellUtil.cloneFamily(cell));
                qualifierStr = Bytes.toString(CellUtil.cloneQualifier(cell));
                byte[] hbaseVal = CellUtil.cloneValue(cell);
                final HBaseColumnSchema hBaseColumnSchema = columnSchema(familyStr, qualifierStr);
                final TypeHandler typeHandler = hBaseColumnSchema.getTypeHandler();
                Object valueObject = typeHandler.toObject(hBaseColumnSchema.getType(), hbaseVal);

                long ts = cell.getTimestamp();
                Date tsDate = new Date(ts);

                HBaseCellResult cellResult = new HBaseCellResult();
                cellResult.setFamilyStr(familyStr);
                cellResult.setQualifierStr(qualifierStr);
                cellResult.setValueObject(valueObject);
                cellResult.setTsDate(tsDate);

                resultList.add(cellResult);
            }
            familyStr = "";
            qualifierStr = "";
            byte[] row = result.getRow();
            rowKeyHandler = hBaseTableConfig.gethBaseTableSchema().getRowKeyHandler();

            RowKey rowKey = rowKeyHandler.convert(row);

            for (HBaseCellResult cell : resultList) {
                cell.setRowKey(rowKey);
            }

            return resultList;

        } catch (Exception e) {
            throw new HBaseOperationsException(
                    "convert result exception. familyStr=" + familyStr
                            + " qualifierStr=" + qualifierStr
                            + " rowKeyHandler=" + rowKeyHandler + " result="
                            + result, e);
        }
    }

    /**
     * 筛选我们需要的字段列表
     * @param hbaseColumnSchemaList 字段列表
     * @param scan scan
     */
    protected  void applyRequestFamilyAndQualifier(List<HBaseColumnSchema> hbaseColumnSchemaList, Scan scan) {
        for (HBaseColumnSchema hbaseColumnSchema : hbaseColumnSchemaList) {
            scan.addColumn(Bytes.toBytes(hbaseColumnSchema.getFamily()),
                    Bytes.toBytes(hbaseColumnSchema.getQualifier()));
        }
    }

    protected void checkTableName(String tableName) {
        Util.checkEquals(tableName, hBaseTableConfig.gethBaseTableSchema().getTableName());
    }

    protected byte[] convertValueToBytes(Object value, HBaseColumnSchema hbaseColumnSchema) {
        TypeHandler typeHandler = hbaseColumnSchema.getTypeHandler();
        return typeHandler.toBytes(hbaseColumnSchema.getType(), value);
    }

    @Override
    public HBaseTableConfig getHBaseTableConfig() {
        return this.hBaseTableConfig;
    }

    @Override
    public void setHBaseTableConfig(HBaseTableConfig hbaseTableConfig) {
        this.hBaseTableConfig = hbaseTableConfig;
    }

    @Override
    public HBaseSQLRuntimeSetting getHBaseSQLRuntimeSetting() {
        return this.runtimeSetting;
    }

    @Override
    public void setHBaseSQLRuntimeSetting(HBaseSQLRuntimeSetting runtimeSetting) {
        this.runtimeSetting = runtimeSetting;
    }
}
