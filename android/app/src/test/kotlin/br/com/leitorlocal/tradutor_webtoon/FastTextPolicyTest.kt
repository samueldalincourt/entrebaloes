package br.com.leitorlocal.tradutor_webtoon

import org.junit.Assert.*
import org.junit.Test

class FastTextPolicyTest {
    @Test fun repairsCollapsedLeadingEllipsisBeforeTranslating() {
        val prepared = FastTextPolicy.prepare(".AMONG THE PESTS THAT CALL NEST HOME.", listOf("Nest"))
        assertEquals("among the pests that call ZXQTERM0ZXQ home.", prepared.text)
        assertEquals("…entre as pragas que chamam Nest de lar.",
            FastTextPolicy.restore("entre as pragas que chamam zxqterm0zxq de lar.", prepared))
    }
    @Test fun protectsWholeNamesWithoutReplacingPartsOfOtherWords() {
        val prepared = FastTextPolicy.prepare("NEST AND NESTING", listOf("Nest"))
        assertEquals("ZXQTERM0ZXQ and nesting", prepared.text)
        assertEquals("Nest e aninhamento", FastTextPolicy.restore("ZXQ TERM 0 ZXQ e aninhamento", prepared))
    }
    @Test fun keepsApostrophesAndMixedCaseNames() {
        val prepared = FastTextPolicy.prepare("'Cause Luffy said so!", listOf("Luffy"))
        assertEquals("'Cause ZXQTERM0ZXQ said so!", prepared.text)
        assertEquals("Porque Luffy disse!", FastTextPolicy.restore("Porque ZXQTERM0ZXQ disse!", prepared))
    }
}
