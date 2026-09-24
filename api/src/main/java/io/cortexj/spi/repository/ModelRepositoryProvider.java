package io.cortexj.spi.repository;

import io.cortexj.model.ModelReference;

public interface ModelRepositoryProvider {

    RepositoryId id();

    boolean supports(ModelReference reference);

    ResolvedModel resolve(ModelReference reference, ResolutionContext context);
}
