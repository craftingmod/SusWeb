package alyac.kotlin.susweb

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
