package alyac.kotlin.susweb.ruliweb

data class SessionResponse(
    val success: Boolean,
    val message: String,
    val unreadNotify: Int?,
)

data class RuliProfile(
    val nickname: String,
    val memberId: Int, // member_srl
    val profileImage: String?, // url
)