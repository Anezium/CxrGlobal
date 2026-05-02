package com.example.cxrglobal.auth

sealed class AuthResult {
    data class AuthSuccess(val token: String) : AuthResult()
    object AuthFail : AuthResult()
    object AuthCancel : AuthResult()
}
