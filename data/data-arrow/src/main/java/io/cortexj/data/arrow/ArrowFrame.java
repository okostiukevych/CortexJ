package io.cortexj.data.arrow;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowFileReader;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.ipc.SeekableReadChannel;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Arrow IPC file adapter (ADR-019): round-trip between Arrow files and
 * CortexJ frames. Supported types: BIGINT, DOUBLE, BOOLEAN, VARCHAR.
 * The library never leaks into data-api signatures.
 */
public final class ArrowFrame {

    private ArrowFrame() {
    }

    public static Frame read(Path path) throws IOException {
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
             FileInputStream input = new FileInputStream(path.toFile());
             ArrowFileReader reader = new ArrowFileReader(
                     new SeekableReadChannel(input.getChannel()), allocator)) {
            List<String> names = new ArrayList<String>();
            List<ColType> types = new ArrayList<ColType>();
            for (Field field : reader.getVectorSchemaRoot().getSchema().getFields()) {
                names.add(field.getName());
                types.add(columnType(field));
            }
            List<List<Object>> columns = new ArrayList<List<Object>>();
            for (int i = 0; i < names.size(); i++) {
                columns.add(new ArrayList<Object>());
            }
            while (reader.loadNextBatch()) {
                VectorSchemaRoot root = reader.getVectorSchemaRoot();
                for (int c = 0; c < names.size(); c++) {
                    readColumn(root, c, types.get(c), columns.get(c));
                }
            }
            List<Column<?>> frameColumns = new ArrayList<Column<?>>(names.size());
            for (int c = 0; c < names.size(); c++) {
                frameColumns.add(typedColumn(names.get(c), types.get(c), columns.get(c)));
            }
            return new SimpleFrame(new Schema(names, types), frameColumns);
        }
    }

    public static void write(Frame frame, Path path) throws IOException {
        List<String> names = frame.schema().names();
        List<ColType> types = frame.schema().types();
        List<Field> fields = new ArrayList<Field>(names.size());
        for (int c = 0; c < names.size(); c++) {
            fields.add(new Field(names.get(c),
                    new FieldType(true, arrowType(types.get(c)), null), null));
        }
        org.apache.arrow.vector.types.pojo.Schema schema =
                new org.apache.arrow.vector.types.pojo.Schema(fields, null);
        try (BufferAllocator allocator = new RootAllocator(Long.MAX_VALUE);
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            for (int c = 0; c < names.size(); c++) {
                writeColumn(root, c, types.get(c), frame.column(names.get(c)));
            }
            root.setRowCount(frame.rowCount());
            try (FileOutputStream output = new FileOutputStream(path.toFile());
                 ArrowFileWriter writer = new ArrowFileWriter(
                         root, null, Channels.newChannel(output))) {
                writer.start();
                writer.writeBatch();
                writer.end();
            }
        }
    }

    private static ArrowType arrowType(ColType type) {
        switch (type) {
            case LONG:
                return new ArrowType.Int(64, true);
            case DOUBLE:
                return new ArrowType.FloatingPoint(org.apache.arrow.vector.types.FloatingPointPrecision.DOUBLE);
            case BOOLEAN:
                return new ArrowType.Bool();
            case STRING:
            default:
                return new ArrowType.Utf8();
        }
    }

    private static ColType columnType(Field field) throws IOException {
        ArrowType type = field.getType();
        if (type instanceof ArrowType.Int) {
            return ColType.LONG;
        }
        if (type instanceof ArrowType.FloatingPoint) {
            return ColType.DOUBLE;
        }
        if (type instanceof ArrowType.Bool) {
            return ColType.BOOLEAN;
        }
        if (type instanceof ArrowType.Utf8) {
            return ColType.STRING;
        }
        throw new IOException("unsupported Arrow type " + type + ": " + field.getName());
    }

    private static void readColumn(VectorSchemaRoot root, int index, ColType type,
                                   List<Object> values) {
        switch (type) {
            case LONG: {
                BigIntVector vector = (BigIntVector) root.getVector(index);
                for (int r = 0; r < root.getRowCount(); r++) {
                    values.add(vector.isNull(r) ? null : Long.valueOf(vector.get(r)));
                }
                break;
            }
            case DOUBLE: {
                Float8Vector vector = (Float8Vector) root.getVector(index);
                for (int r = 0; r < root.getRowCount(); r++) {
                    values.add(vector.isNull(r) ? null : Double.valueOf(vector.get(r)));
                }
                break;
            }
            case BOOLEAN: {
                BitVector vector = (BitVector) root.getVector(index);
                for (int r = 0; r < root.getRowCount(); r++) {
                    values.add(vector.isNull(r) ? null : Boolean.valueOf(vector.get(r) == 1));
                }
                break;
            }
            case STRING:
            default: {
                VarCharVector vector = (VarCharVector) root.getVector(index);
                for (int r = 0; r < root.getRowCount(); r++) {
                    values.add(vector.isNull(r) ? null
                            : new String(vector.get(r), StandardCharsets.UTF_8));
                }
                break;
            }
        }
    }

    private static void writeColumn(VectorSchemaRoot root, int index, ColType type,
                                    Column<?> column) {
        switch (type) {
            case LONG: {
                BigIntVector vector = (BigIntVector) root.getVector(index);
                vector.allocateNew(column.size());
                for (int r = 0; r < column.size(); r++) {
                    Long value = (Long) column.get(r);
                    if (value == null) {
                        vector.setNull(r);
                    } else {
                        vector.setSafe(r, value.longValue());
                    }
                }
                vector.setValueCount(column.size());
                break;
            }
            case DOUBLE: {
                Float8Vector vector = (Float8Vector) root.getVector(index);
                vector.allocateNew(column.size());
                for (int r = 0; r < column.size(); r++) {
                    Double value = (Double) column.get(r);
                    if (value == null) {
                        vector.setNull(r);
                    } else {
                        vector.setSafe(r, value.doubleValue());
                    }
                }
                vector.setValueCount(column.size());
                break;
            }
            case BOOLEAN: {
                BitVector vector = (BitVector) root.getVector(index);
                vector.allocateNew(column.size());
                for (int r = 0; r < column.size(); r++) {
                    Boolean value = (Boolean) column.get(r);
                    if (value == null) {
                        vector.setNull(r);
                    } else {
                        vector.setSafe(r, value.booleanValue() ? 1 : 0);
                    }
                }
                vector.setValueCount(column.size());
                break;
            }
            case STRING:
            default: {
                VarCharVector vector = (VarCharVector) root.getVector(index);
                vector.allocateNew();
                for (int r = 0; r < column.size(); r++) {
                    String value = (String) column.get(r);
                    if (value == null) {
                        vector.setNull(r);
                    } else {
                        vector.setSafe(r, value.getBytes(StandardCharsets.UTF_8));
                    }
                }
                vector.setValueCount(column.size());
                break;
            }
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
