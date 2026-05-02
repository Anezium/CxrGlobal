package com.example.cxrglobal

sealed class AuthResult {
    data class AuthSuccess(val token: String) : AuthResult()
    object AuthFail : AuthResult()
    object AuthCancel : AuthResult()
}
