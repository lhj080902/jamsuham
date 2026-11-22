package kr.jamsuham.core

/**
 * 엔진(core)이 화면에 보내는 문구의 언어. 앱이 설정(한국어/English)에 맞춰 [english] 를 바꾼다.
 * 기본은 한국어 (PC 테스트도 한국어 문구를 기준으로 확인한다).
 */
object Lang {
    @Volatile
    var english: Boolean = false
}

/** 한국어/영어 문구 고르기 (core 용. 앱은 kr.jamsuham.app.tr 를 쓴다) */
fun tr(ko: String, en: String): String = if (Lang.english) en else ko
