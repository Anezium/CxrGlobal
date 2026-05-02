package com.example.cxrglobal

object CxrDefs {

    enum class CXRSessionType { NONE, CUSTOMVIEW, CUSTOMAPP }

    class CXRSession @JvmOverloads constructor(
        @JvmField var sessionType: CXRSessionType,
        @JvmField var customAppPackageName: String? = null,
    ) {
        override fun toString(): String =
            "CXRSession(sessionType=$sessionType, customAppPackageName=$customAppPackageName)"
    }
}

data class IconInfo(var name: String, var data: String)
