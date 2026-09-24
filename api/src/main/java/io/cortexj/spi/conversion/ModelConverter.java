package io.cortexj.spi.conversion;

public interface ModelConverter {

    ConverterId id();

    ConversionSupport evaluate(ConversionRequest request);

    ConversionResult convert(ConversionRequest request);
}
