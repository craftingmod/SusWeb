package alyac.kotlin.susweb.session

import alyac.kotlin.susweb.BASE_URL
import alyac.kotlin.susweb.USER_URL

internal const val LOGIN_URL =
    "${USER_URL}/member/login?redirect_url=https%3A%2F%2Fbbs.ruliweb.com%2Fmember%2Fmypage"
internal const val MY_PAGE_URL = "${BASE_URL}/member/mypage"

internal expect val ruliwebClientMode: String
