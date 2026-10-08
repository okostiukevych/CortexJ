package io.cortexj.data;

public interface Column<T> {

    String name();

    Class<T> type();

    int size();

    T get(int index);
}
