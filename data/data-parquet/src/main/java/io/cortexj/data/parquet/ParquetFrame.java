package io.cortexj.data.parquet;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.example.GroupReadSupport;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.Type;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ParquetFrame {

    private ParquetFrame() {
    }

    public static Frame read(Path path) throws IOException {
        org.apache.hadoop.fs.Path hadoopPath =
                new org.apache.hadoop.fs.Path(path.toUri());
        MessageType schema = readSchema(hadoopPath);
        List<String> names = new ArrayList<String>();
        List<ColType> types = new ArrayList<ColType>();
        for (Type field : schema.getFields()) {
            names.add(field.getName());
            types.add(columnType(field));
        }
        List<List<Object>> columns = new ArrayList<List<Object>>();
        for (int i = 0; i < names.size(); i++) {
            columns.add(new ArrayList<Object>());
        }
        ParquetReader.Builder<Group> builder =
                ParquetReader.builder(new GroupReadSupport(), hadoopPath);
        ParquetReader<Group> reader = builder.build();
        try {
            Group group;
            while ((group = reader.read()) != null) {
                for (int i = 0; i < names.size(); i++) {
                    columns.get(i).add(cell(group, names.get(i), types.get(i)));
                }
            }
        } finally {
            reader.close();
        }
        List<Column<?>> frameColumns = new ArrayList<Column<?>>(names.size());
        for (int i = 0; i < names.size(); i++) {
            frameColumns.add(typedColumn(names.get(i), types.get(i), columns.get(i)));
        }
        return new SimpleFrame(new Schema(names, types), frameColumns);
    }

    private static MessageType readSchema(org.apache.hadoop.fs.Path path) throws IOException {
        ParquetFileReader reader = ParquetFileReader.open(
                new org.apache.hadoop.conf.Configuration(), path);
        try {
            ParquetMetadata metadata = reader.getFooter();
            return metadata.getFileMetaData().getSchema();
        } finally {
            reader.close();
        }
    }

    private static ColType columnType(Type field) throws IOException {
        if (!field.isPrimitive()) {
            throw new IOException("nested Parquet field is not supported: " + field.getName());
        }
        if (field.isRepetition(Type.Repetition.REPEATED)) {
            throw new IOException("repeated Parquet field is not supported: " + field.getName());
        }
        PrimitiveType.PrimitiveTypeName primitive =
                field.asPrimitiveType().getPrimitiveTypeName();
        switch (primitive) {
            case INT32:
            case INT64:
                return ColType.LONG;
            case FLOAT:
            case DOUBLE:
                return ColType.DOUBLE;
            case BOOLEAN:
                return ColType.BOOLEAN;
            case BINARY:
            case FIXED_LEN_BYTE_ARRAY:
            case INT96:
                return ColType.STRING;
            default:
                throw new IOException("unsupported Parquet type " + primitive + ": " + field.getName());
        }
    }

    private static Object cell(Group group, String name, ColType type) throws IOException {
        if (group.getFieldRepetitionCount(name) == 0) {
            return null;
        }
        switch (type) {
            case LONG:
                // INT32 and INT64 both map to LONG; Group accessors are
                // type-strict, so fall back across the int widths
                try {
                    return Long.valueOf(group.getLong(name, 0));
                } catch (RuntimeException int32) {
                    return Long.valueOf(group.getInteger(name, 0));
                }
            case DOUBLE:
                try {
                    return Double.valueOf(group.getDouble(name, 0));
                } catch (RuntimeException float32) {
                    return Double.valueOf(group.getFloat(name, 0));
                }
            case BOOLEAN:
                return Boolean.valueOf(group.getBoolean(name, 0));
            case STRING:
            default:
                return group.getString(name, 0);
        }
    }

    private static Column<?> typedColumn(String name, ColType type, List<Object> values) {
        switch (type) {
            case LONG: {
                List<Long> typed = new ArrayList<Long>(values.size());
                for (Object value : values) {
                    typed.add((Long) value);
                }
                return new SimpleColumn<Long>(name, Long.class, typed);
            }
            case DOUBLE: {
                List<Double> typed = new ArrayList<Double>(values.size());
                for (Object value : values) {
                    typed.add((Double) value);
                }
                return new SimpleColumn<Double>(name, Double.class, typed);
            }
            case BOOLEAN: {
                List<Boolean> typed = new ArrayList<Boolean>(values.size());
                for (Object value : values) {
                    typed.add((Boolean) value);
                }
                return new SimpleColumn<Boolean>(name, Boolean.class, typed);
            }
            case STRING:
            default: {
                List<String> typed = new ArrayList<String>(values.size());
                for (Object value : values) {
                    typed.add((String) value);
                }
                return new SimpleColumn<String>(name, String.class, typed);
            }
        }
    }
}
