package io.cortexj.inference;

import java.util.ArrayList;
import java.util.List;

public final class RerankingRequest {

    private final String query;
    private final List<String> documents;

    private RerankingRequest(String query, List<String> documents) {
        if (query == null) {
            throw new IllegalArgumentException("Reranking query must not be null");
        }
        this.query = query;
        this.documents = new ArrayList<String>(documents);
    }

    public static RerankingRequest of(String query, List<String> documents) {
        return new RerankingRequest(query, documents);
    }

    public String query() {
        return query;
    }

    public List<String> documents() {
        return documents;
    }
}
