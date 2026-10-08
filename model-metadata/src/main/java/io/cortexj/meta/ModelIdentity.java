package io.cortexj.meta;

/**
 * Canonical identity of a logical model: where it comes from plus the
 * resolved immutable revision (null while the revision is unresolved).
 * Java 8 compatible value object, no dependencies.
 */
public final class ModelIdentity {

    private final String id;
    private final String revision;

    private ModelIdentity(String id, String revision) {
        if (id == null) {
            throw new IllegalArgumentException("model id must not be null");
        }
        this.id = id;
        this.revision = revision;
    }

    public static ModelIdentity of(String id, String revision) {
        return new ModelIdentity(id, revision);
    }

    public String id() {
        return id;
    }

    public String revision() {
        return revision;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModelIdentity)) {
            return false;
        }
        ModelIdentity that = (ModelIdentity) other;
        if (!id.equals(that.id)) {
            return false;
        }
        if (revision == null) {
            return that.revision == null;
        }
        return revision.equals(that.revision);
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + (revision == null ? 0 : revision.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return revision == null ? id : id + "@" + revision;
    }
}
