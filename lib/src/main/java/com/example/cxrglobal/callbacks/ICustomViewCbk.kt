package com.example.cxrglobal.callbacks

interface ICustomViewCbk {
    fun onCustomViewOpened() {}
    fun onCustomViewUpdated() {}
    fun onCustomViewClosed() {}
    fun onCustomViewIconsSent() {}
    fun onCustomViewError(code: Int, msg: String?) {}
}
