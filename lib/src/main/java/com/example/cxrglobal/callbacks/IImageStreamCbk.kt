package com.example.cxrglobal.callbacks

interface IImageStreamCbk {
    fun onImageReceived(data: ByteArray) {}
    fun onImageError(code: Int, msg: String?) {}
}
