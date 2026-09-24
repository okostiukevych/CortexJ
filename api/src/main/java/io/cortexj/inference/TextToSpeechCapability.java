package io.cortexj.inference;

public interface TextToSpeechCapability {

    byte[] synthesize(String text, String voiceHint);
}
