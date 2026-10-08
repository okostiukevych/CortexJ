package io.cortexj.data;

import java.util.List;

public interface Frame {

    Schema schema();

    int rowCount();

    Column<?> column(String name);

    Frame head(int rows);

    Frame select(String... names);

    List<List<String>> rows();
}
