package io.cortexj.inference;

public interface SpeechToTextCapability {

    String transcribe(byte[] audio, String formatHint);
}
