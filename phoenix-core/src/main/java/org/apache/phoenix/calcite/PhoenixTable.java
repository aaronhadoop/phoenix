package org.apache.phoenix.calcite;

import java.util.List;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.phoenix.calcite.rel.PhoenixTableScan;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.stats.GuidePostsInfo;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.util.SchemaUtil;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Implementation of Calcite {@link org.apache.calcite.schema.Table} SPI for
 * Phoenix.
 */
public class PhoenixTable extends AbstractTable implements TranslatableTable {
  public final PTable pTable;
  public final List<PColumn> mappedColumns;
  public final ImmutableBitSet pkBitSet;
  public final RelCollation collation;
  public final PhoenixConnection pc;
  
  public static List<PColumn> getMappedColumns(PTable pTable) {
      if (pTable.getBucketNum() == null
              && !pTable.isMultiTenant()
              && pTable.getViewIndexId() == null) {
          return pTable.getColumns();
      }
      
      List<PColumn> columns = Lists.newArrayList(pTable.getColumns());
      if (pTable.getViewIndexId() != null) {
          columns.remove((pTable.getBucketNum() == null ? 0 : 1) + (pTable.isMultiTenant() ? 1 : 0));
      }
      if (pTable.isMultiTenant()) {
          columns.remove(pTable.getBucketNum() == null ? 0 : 1);
      }
      if (pTable.getBucketNum() != null) {
          columns.remove(0);
      }
      return columns;
  }

  public PhoenixTable(PhoenixConnection pc, PTable pTable) {
      this.pc = Preconditions.checkNotNull(pc);
      this.pTable = Preconditions.checkNotNull(pTable);
      this.mappedColumns = getMappedColumns(pTable);
      List<Integer> pkPositions = Lists.<Integer> newArrayList();
      List<RelFieldCollation> fieldCollations = Lists.<RelFieldCollation> newArrayList();
      for (int i = 0; i < mappedColumns.size(); i++) {
          PColumn column = mappedColumns.get(i);
          if (SchemaUtil.isPKColumn(column)) {
              SortOrder sortOrder = column.getSortOrder();
              pkPositions.add(i);
              fieldCollations.add(new RelFieldCollation(i, sortOrder == SortOrder.ASC ? Direction.ASCENDING : Direction.DESCENDING));
          }
      }
      this.pkBitSet = ImmutableBitSet.of(pkPositions);
      this.collation = RelCollationTraitDef.INSTANCE.canonize(RelCollations.of(fieldCollations));
    }
    
    public PTable getTable() {
    	return pTable;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        final RelDataTypeFactory.FieldInfoBuilder builder = typeFactory.builder();
        for (int i = 0; i < mappedColumns.size(); i++) {
            PColumn pColumn = mappedColumns.get(i);
            final PDataType baseType = 
                    pColumn.getDataType().isArrayType() ?
                            PDataType.fromTypeId(pColumn.getDataType().getSqlType() - PDataType.ARRAY_TYPE_BASE) 
                          : pColumn.getDataType();
            final int sqlTypeId = baseType.getResultSetSqlType();
            final PDataType pDataType = PDataType.fromTypeId(sqlTypeId);
            final SqlTypeName sqlTypeName1 = SqlTypeName.valueOf(pDataType.getSqlTypeName());
            final Integer maxLength = pColumn.getMaxLength();
            final Integer scale = pColumn.getScale();
            RelDataType type;
            if (maxLength != null && scale != null) {
                type = typeFactory.createSqlType(sqlTypeName1, maxLength, scale);
            } else if (maxLength != null) {
                type = typeFactory.createSqlType(sqlTypeName1, maxLength);
            } else {
                type = typeFactory.createSqlType(sqlTypeName1);
            }
            if (pColumn.getDataType().isArrayType()) {
                final Integer arraySize = pColumn.getArraySize();
                type = typeFactory.createArrayType(type, arraySize == null ? -1 : arraySize);
            }
            builder.add(pColumn.getName().getString(), type);
            builder.nullable(pColumn.isNullable());
        }
        return builder.build();
    }

    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
        return PhoenixTableScan.create(context.getCluster(), relOptTable);
    }

    @Override
    public Statistic getStatistic() {
        return new Statistic() {
            @Override
            public Double getRowCount() {
                byte[] emptyCf = SchemaUtil.getEmptyColumnFamily(pTable);
                GuidePostsInfo info = pTable.getTableStats().getGuidePosts().get(emptyCf);
                long rowCount = info == null ? 0 : info.getRowCount();
                
                // Return an non-zero value to make the query plans stable.
                // TODO remove "* 10.0" which is for test purpose.
                return rowCount > 0 ? rowCount * 10.0 : 100.0;
            }

            @Override
            public boolean isKey(ImmutableBitSet immutableBitSet) {
                return immutableBitSet.contains(pkBitSet);
            }

            @Override
            public List<RelCollation> getCollations() {
                return ImmutableList.<RelCollation>of(collation);
            }

            @Override
            public RelDistribution getDistribution() {
                return RelDistributions.RANDOM_DISTRIBUTED;
            }
        };
    }
}