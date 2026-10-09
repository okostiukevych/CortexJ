package io.cortexj.research;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Model and dataset cards plus citation metadata (ADR-028): the
 * documented facts about a model/dataset, enough for a research
 * manifest. Immutable; missing data is explicit (empty), never
 * fabricated.
 */
public final class Cards {

    private Cards() {
    }

    /**
     * A model card: identity, intended use, limitations, license,
     * citation.
     */
    public static final class ModelCard {

        private final io.cortexj.meta.ModelIdentity identity;
        private final String intendedUse;
        private final String limitations;
        private final String license;
        private final Citation citation;

        public ModelCard(io.cortexj.meta.ModelIdentity identity, String intendedUse,
                         String limitations, String license, Citation citation) {
            if (identity == null) {
                throw new IllegalArgumentException("model identity is required");
            }
            this.identity = identity;
            this.intendedUse = orEmpty(intendedUse);
            this.limitations = orEmpty(limitations);
            this.license = orEmpty(license);
            this.citation = citation;
        }

        public io.cortexj.meta.ModelIdentity identity() {
            return identity;
        }

        public String intendedUse() {
            return intendedUse;
        }

        public String limitations() {
            return limitations;
        }

        public String license() {
            return license;
        }

        public Citation citation() {
            return citation;
        }

        public String render() {
            StringBuilder card = new StringBuilder();
            card.append("Model: ").append(identity).append('\n');
            card.append("Intended use: ").append(intendedUse).append('\n');
            card.append("Limitations: ").append(limitations).append('\n');
            card.append("License: ").append(license).append('\n');
            if (citation != null) {
                card.append("Citation: ").append(citation.render()).append('\n');
            }
            return card.toString();
        }
    }

    /**
     * A dataset card: fingerprint, source, size, license, citation.
     */
    public static final class DatasetCard {

        private final String fingerprint;
        private final String source;
        private final long rows;
        private final String license;
        private final Citation citation;

        public DatasetCard(String fingerprint, String source, long rows,
                           String license, Citation citation) {
            if (fingerprint == null || fingerprint.isEmpty()) {
                throw new IllegalArgumentException("dataset fingerprint is required");
            }
            this.fingerprint = fingerprint;
            this.source = orEmpty(source);
            this.rows = rows;
            this.license = orEmpty(license);
            this.citation = citation;
        }

        public String fingerprint() {
            return fingerprint;
        }

        public String source() {
            return source;
        }

        public long rows() {
            return rows;
        }

        public String license() {
            return license;
        }

        public Citation citation() {
            return citation;
        }

        public String render() {
            StringBuilder card = new StringBuilder();
            card.append("Dataset fingerprint: ").append(fingerprint).append('\n');
            card.append("Source: ").append(source).append('\n');
            card.append("Rows: ").append(rows).append('\n');
            card.append("License: ").append(license).append('\n');
            if (citation != null) {
                card.append("Citation: ").append(citation.render()).append('\n');
            }
            return card.toString();
        }
    }

    /**
     * Citation metadata: authors, title, year, venue, identifier.
     */
    public static final class Citation {

        private final List<String> authors;
        private final String title;
        private final int year;
        private final String venue;
        private final String identifier;

        public Citation(List<String> authors, String title, int year,
                        String venue, String identifier) {
            if (title == null || title.isEmpty()) {
                throw new IllegalArgumentException("citation title is required");
            }
            this.authors = authors == null
                    ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(authors));
            this.title = title;
            this.year = year;
            this.venue = orEmpty(venue);
            this.identifier = orEmpty(identifier);
        }

        public List<String> authors() {
            return authors;
        }

        public String title() {
            return title;
        }

        public int year() {
            return year;
        }

        public String venue() {
            return venue;
        }

        public String identifier() {
            return identifier;
        }

        public String render() {
            StringBuilder citation = new StringBuilder();
            for (int i = 0; i < authors.size(); i++) {
                if (i > 0) {
                    citation.append(", ");
                }
                citation.append(authors.get(i));
            }
            if (!authors.isEmpty()) {
                citation.append(". ");
            }
            citation.append(title);
            if (year > 0) {
                citation.append(" (").append(year).append(')');
            }
            if (!venue.isEmpty()) {
                citation.append(". ").append(venue);
            }
            if (!identifier.isEmpty()) {
                citation.append(". ").append(identifier);
            }
            return citation.toString();
        }
    }

    /**
     * An experiment bundle: manifest plus cards plus checksums — the
     * exportable research artifact.
     */
    public static final class ExperimentBundle {

        private final io.cortexj.provenance.ProvenanceManifest manifest;
        private final ModelCard modelCard;
        private final DatasetCard datasetCard;
        private final Map<String, String> checksums;

        public ExperimentBundle(io.cortexj.provenance.ProvenanceManifest manifest,
                                ModelCard modelCard, DatasetCard datasetCard,
                                Map<String, String> checksums) {
            if (manifest == null) {
                throw new IllegalArgumentException("provenance manifest is required");
            }
            this.manifest = manifest;
            this.modelCard = modelCard;
            this.datasetCard = datasetCard;
            this.checksums = checksums == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(checksums));
        }

        public io.cortexj.provenance.ProvenanceManifest manifest() {
            return manifest;
        }

        public ModelCard modelCard() {
            return modelCard;
        }

        public DatasetCard datasetCard() {
            return datasetCard;
        }

        public Map<String, String> checksums() {
            return checksums;
        }

        public boolean checksumPresent() {
            return !checksums.isEmpty();
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
