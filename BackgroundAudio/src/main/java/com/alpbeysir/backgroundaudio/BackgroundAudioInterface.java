package com.alpbeysir.backgroundaudio;

public interface BackgroundAudioInterface {
    void Started();
    void Stopped();
    void Paused();
    void Resumed();
    void Info(int what, int extra);
    void Error(int what, int extra);
    void Prepared();
}
