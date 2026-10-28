package br.com.leitorlocal.tradutor_webtoon

import org.junit.Assert.*
import org.junit.Test

class BrowserTargetPolicyTest {
    @Test fun acceptsOnlyOfficialHttpsReaderAddresses() {
        assertNotNull(BrowserTargetPolicy.webtoonUrl("www.webtoons.com/en/action/example/viewer?episode_no=1"))
        assertNotNull(BrowserTargetPolicy.webtoonUrl("https://m.webtoons.com/en/"))
        assertNotNull(BrowserTargetPolicy.webtoonUrl("webtoons.com"))
    }
    @Test fun rejectsOtherTabsAndLookalikeHosts() {
        listOf(null, "", "aistudio.google.com/api-keys", "https://webtoons.com.evil.example/", "https://webtoons.com@evil.example/",
            "https://evil.example/?url=webtoons.com", "javascript:alert(1)", "http://www.webtoons.com", "https://www.webtoons.com:444/", "webtoons.com search")
            .forEach { assertNull(it, BrowserTargetPolicy.webtoonUrl(it)) }
    }
}
