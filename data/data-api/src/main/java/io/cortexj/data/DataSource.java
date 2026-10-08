package io.cortexj.data;

import java.io.IOException;

public interface DataSource<T> {

    Dataset<T> read() throws IOException;
}
