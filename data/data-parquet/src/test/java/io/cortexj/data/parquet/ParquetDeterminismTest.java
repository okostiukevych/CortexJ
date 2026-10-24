package io.cortexj.data.parquet;

import io.cortexj.data.ColType;
import io.cortexj.data.Dataset;
import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import io.cortexj.data.FrameDataset;
import io.cortexj.data.Split;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.SimpleGroupFactory;
import org.apache.parquet.hadoop.example.ExampleParquetWriter;
import org.apache.parquet.io.OutputFile;
import org.apache.parquet.io.PositionOutputStream;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParquetDeterminismTest {

    /**
     * NIO-backed {@link OutputFile}. Writing through Hadoop's
     * {@code LocalFileSystem} would shell out to {@code chgrp} on POSIX and to
     * {@code winutils.exe} on Windows (a native binary this project cannot ship),
     * so the writer is pointed straight at the file.
     */
    private static final class NioOutputFile implements OutputFile {

        private final Path path;

        private NioOutputFile(Path path) {
            this.path = path;
        }

        @Override
        public PositionOutputStream create(long blockSizeHint) throws java.io.IOException {
            return new NioPositionOutputStream(
                    Files.newOutputStream(path,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE));
        }

        @Override
        public PositionOutputStream createOrOverwrite(long blockSizeHint)
                throws java.io.IOException {
            return create(blockSizeHint);
        }

        @Override
        public boolean supportsBlockSize() {
            return false;
        }

        @Override
        public long defaultBlockSize() {
            return 0L;
        }

        @Override
        public String getPath() {
            return path.toString();
        }
    }

    private static final class NioPositionOutputStream extends PositionOutputStream {

        private final OutputStream delegate;
        private long position;

        private NioPositionOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public long getPos() {
            return position;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            delegate.write(b);
            position++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            delegate.write(b, off, len);
            position += len;
        }

        @Override
        public void flush() throws java.io.IOException {
            delegate.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            delegate.close();
        }
    }

    private static final MessageType SCHEMA = MessageTypeParser.parseMessageType(
            "message reviews {"
                    + " required int64 id;"
                    + " required double score;"
                    + " required boolean verified;"
                    + " required binary text (UTF8);"
                    + "}");

    private static Path writeReviews() throws Exception {
        Path file = Files.createTempFile("reviews", ".parquet");
        // ParquetWriter refuses to overwrite: remove the empty temp file
        Files.deleteIfExists(file);
        ExampleParquetWriter.Builder builder = ExampleParquetWriter.builder(new NioOutputFile(file))
                .withType(SCHEMA);
        org.apache.parquet.hadoop.ParquetWriter<Group> writer = builder.build();
        try {
            SimpleGroupFactory factory = new SimpleGroupFactory(SCHEMA);
            Object[][] rows = {
                    {1L, 4.5d, true, "great"},
                    {2L, 1.0d, false, "bad"},
                    {3L, 3.0d, true, "ok"},
                    {4L, 5.0d, true, "superb"},
                    {5L, 2.5d, false, "meh"}
            };
            for (Object[] row : rows) {
                Group group = factory.newGroup();
                group.append("id", (Long) row[0]);
                group.append("score", (Double) row[1]);
                group.append("verified", (Boolean) row[2]);
                group.append("text", (String) row[3]);
                writer.write(group);
            }
        } finally {
            writer.close();
        }
        return file;
    }

    @Test
    void parquetReadIsTypedAndDeterministic() throws Exception {
        Path file = writeReviews();
        try {
            Frame frame = ParquetFrame.read(file);
            assertEquals(Arrays.asList("id", "score", "verified", "text"), frame.schema().names());
            assertEquals(
                    Arrays.asList(ColType.LONG, ColType.DOUBLE, ColType.BOOLEAN, ColType.STRING),
                    frame.schema().types());
            assertEquals(5, frame.rowCount());
            assertEquals(Long.valueOf(1L), frame.column("id").get(0));
            assertEquals("great", frame.column("text").get(0));
            // spike G: read → batch deterministically → fingerprint twice
            FrameDataset dataset = new FrameDataset(frame);
            List<Dataset<List<String>>> pages = dataset.batches(2);
            assertEquals(3, pages.size());
            assertEquals(2L, pages.get(0).count());
            assertEquals(1L, pages.get(2).count());
            assertEquals(Fingerprint.ofFrame(frame), Fingerprint.ofFrame(ParquetFrame.read(file)));
            Split<Dataset<List<String>>, Dataset<List<String>>> split = dataset.split(0.6, 42L);
            assertEquals(5L, split.train().count() + split.test().count());
            assertEquals(split.train().fingerprint(), dataset.split(0.6, 42L).train().fingerprint());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
