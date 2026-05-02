package com.example.cxrglobal.callbacks

interface IAudioStreamCbk {
    /**
     * PCM チャンク到着。`data[offset, offset+length)` に有効なサンプルが入る。
     * フォーマットは 16 kHz / mono / 16-bit signed PCM (公式サンプルの解釈に従う)。
     */
    fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {}
    fun onAudioError(code: Int, msg: String?) {}
    fun onAudioStreamStateChanged(started: Boolean) {}
}
