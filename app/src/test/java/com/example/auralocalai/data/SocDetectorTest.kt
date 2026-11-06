package com.example.auralocalai.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SocDetectorTest {

    @Test
    fun testSamsungDeviceNotMisidentifiedAsQualcomm() {
        // Samsung Galaxy A12 (MediaTek Helio P35, model SM-A125F)
        val (vendor, name, htp) = SocDetector.classifySoc(
            socModel = "MT6765",
            hardware = "mt6765",
            board = "a12",
            device = "a12",
            product = "SM-A125F"
        )

        assertEquals("Must classify as MediaTek, not Qualcomm", SocVendor.MEDIATEK, vendor)
        assertNotEquals("Must NOT classify as Qualcomm Snapdragon", SocVendor.QUALCOMM, vendor)
        assertEquals("HTP version for MediaTek must be UNKNOWN", HtpVersion.UNKNOWN, htp)
    }

    @Test
    fun testQualcommFlagshipsClassifiedWithCorrectHtp() {
        // Snapdragon 8 Elite (SM8750)
        val (v8Elite, name8Elite, htp8Elite) = SocDetector.classifySoc(
            socModel = "SM8750",
            hardware = "qcom",
            board = "sun",
            device = "sun",
            product = "flagship"
        )
        assertEquals(SocVendor.QUALCOMM, v8Elite)
        assertEquals(HtpVersion.V79, htp8Elite)
        assertTrue(name8Elite.contains("Snapdragon 8 Elite"))

        // Snapdragon 8 Gen 3 (SM8650)
        val (v8Gen3, name8Gen3, htp8Gen3) = SocDetector.classifySoc(
            socModel = "SM8650",
            hardware = "qcom",
            board = "pineapple",
            device = "pineapple",
            product = "flagship"
        )
        assertEquals(SocVendor.QUALCOMM, v8Gen3)
        assertEquals(HtpVersion.V75, htp8Gen3)
        assertTrue(name8Gen3.contains("Snapdragon 8 Gen 3"))

        // Snapdragon 8 Gen 2 (SM8550)
        val (v8Gen2, name8Gen2, htp8Gen2) = SocDetector.classifySoc(
            socModel = "SM8550",
            hardware = "qcom",
            board = "kalama",
            device = "kalama",
            product = "flagship"
        )
        assertEquals(SocVendor.QUALCOMM, v8Gen2)
        assertEquals(HtpVersion.V73, htp8Gen2)
        assertTrue(name8Gen2.contains("Snapdragon 8 Gen 2"))

        // Snapdragon 8 Gen 1 (SM8450)
        val (v8Gen1, _, htp8Gen1) = SocDetector.classifySoc(
            socModel = "SM8450",
            hardware = "qcom",
            board = "taro",
            device = "taro",
            product = "flagship"
        )
        assertEquals(SocVendor.QUALCOMM, v8Gen1)
        assertEquals(HtpVersion.V69, htp8Gen1)

        // Snapdragon 888 (SM8350)
        val (v888, _, htp888) = SocDetector.classifySoc(
            socModel = "SM8350",
            hardware = "qcom",
            board = "lahaina",
            device = "lahaina",
            product = "flagship"
        )
        assertEquals(SocVendor.QUALCOMM, v888)
        assertEquals(HtpVersion.V68, htp888)
    }

    @Test
    fun testQualcommEntryLevelHtpIsUnknown() {
        // Snapdragon 685 (SM6225)
        val (v685, name685, htp685) = SocDetector.classifySoc(
            socModel = "SM6225",
            hardware = "qcom",
            board = "bengal",
            device = "onyx",
            product = "redmi_note"
        )
        assertEquals(SocVendor.QUALCOMM, v685)
        assertEquals("Entry-level Snapdragon 685 has no Hexagon HTP runtime", HtpVersion.UNKNOWN, htp685)
        assertTrue(name685.contains("Snapdragon 685"))

        // Snapdragon 695 5G (SM6375)
        val (v695, _, htp695) = SocDetector.classifySoc(
            socModel = "SM6375",
            hardware = "qcom",
            board = "holi",
            device = "holi",
            product = "budget_5g"
        )
        assertEquals(SocVendor.QUALCOMM, v695)
        assertEquals(HtpVersion.UNKNOWN, htp695)

        // Snapdragon 480 (SM4350)
        val (v480, _, htp480) = SocDetector.classifySoc(
            socModel = "SM4350",
            hardware = "qcom",
            board = "holi",
            device = "holi",
            product = "entry"
        )
        assertEquals(SocVendor.QUALCOMM, v480)
        assertEquals(HtpVersion.UNKNOWN, htp480)
    }

    @Test
    fun testMediaTekDimensityDetection() {
        val (v9400, name9400, _) = SocDetector.classifySoc(
            socModel = "MT6991",
            hardware = "mt6991",
            board = "k6991",
            device = "k6991",
            product = "flagship_mtk"
        )
        assertEquals(SocVendor.MEDIATEK, v9400)
        assertTrue(name9400.contains("Dimensity 9400"))

        val (v9300, name9300, _) = SocDetector.classifySoc(
            socModel = "MT6989",
            hardware = "mt6989",
            board = "k6989",
            device = "k6989",
            product = "flagship_mtk"
        )
        assertEquals(SocVendor.MEDIATEK, v9300)
        assertTrue(name9300.contains("Dimensity 9300"))
    }

    @Test
    fun testExynosDetectionDoesNotMatchBareNumbers() {
        // MediaTek device with bare "2100" in build string must not be misclassified as Exynos 2100
        val (vMtk, _, _) = SocDetector.classifySoc(
            socModel = "MT6877",
            hardware = "mt6877",
            board = "mt6877",
            device = "device2100",
            product = "build_2100"
        )
        assertEquals("Must remain MediaTek and not flip to Exynos", SocVendor.MEDIATEK, vMtk)

        // Real Samsung Exynos 2400 (S5E9945)
        val (vExynos2400, name2400, _) = SocDetector.classifySoc(
            socModel = "S5E9945",
            hardware = "exynos",
            board = "s5e9945",
            device = "e3",
            product = "galaxy_s24"
        )
        assertEquals(SocVendor.SAMSUNG_EXYNOS, vExynos2400)
        assertTrue(name2400.contains("Exynos 2400"))

        // Real Samsung Exynos 2200 (S5E9925)
        val (vExynos2200, name2200, _) = SocDetector.classifySoc(
            socModel = "S5E9925",
            hardware = "exynos",
            board = "s5e9925",
            device = "r0s",
            product = "galaxy_s22"
        )
        assertEquals(SocVendor.SAMSUNG_EXYNOS, vExynos2200)
        assertTrue(name2200.contains("Exynos 2200"))
    }

    @Test
    fun testGoogleTensorDetection() {
        // Tensor G3 (Pixel 8 / Zuma)
        val (vTensorG3, nameG3, _) = SocDetector.classifySoc(
            socModel = "ZUMA",
            hardware = "zuma",
            board = "shiba",
            device = "shiba",
            product = "pixel8"
        )
        assertEquals(SocVendor.GOOGLE_TENSOR, vTensorG3)
        assertTrue(nameG3.contains("Tensor G3"))

        // Tensor G2 (Pixel 7 / Cheetah)
        val (vTensorG2, nameG2, _) = SocDetector.classifySoc(
            socModel = "GS201",
            hardware = "tensor",
            board = "cheetah",
            device = "cheetah",
            product = "pixel7"
        )
        assertEquals(SocVendor.GOOGLE_TENSOR, vTensorG2)
        assertTrue(nameG2.contains("Tensor G2"))
    }
}

