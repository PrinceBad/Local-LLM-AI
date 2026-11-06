package com.example.auralocalai.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

private const val TAG = "SocDetector"

enum class SocVendor {
    QUALCOMM,
    MEDIATEK,
    GOOGLE_TENSOR,
    SAMSUNG_EXYNOS,
    UNKNOWN
}

enum class HtpVersion(val versionNumber: Int, val label: String) {
    UNKNOWN(0, "Unknown"),
    V68(68, "HTP v68 (Snapdragon 888 / 778G)"),
    V69(69, "HTP v69 (Snapdragon 8 Gen 1 / 8+ Gen 1)"),
    V73(73, "HTP v73 (Snapdragon 8 Gen 2 / 7+ Gen 2)"),
    V75(75, "HTP v75 (Snapdragon 8 Gen 3 / 8s Gen 3 / 7+ Gen 3)"),
    V79(79, "HTP v79 (Snapdragon 8 Elite / 8s Gen 4 / 7+ Gen 4)"),
    V81(81, "HTP v81 (Snapdragon Next-Gen / Oryon)")
}

data class SocInfo(
    val vendor: SocVendor,
    val socModel: String,
    val marketingName: String,
    val htpVersion: HtpVersion,
    val isNpuHardwarePresent: Boolean,
    val isQnnRuntimeAvailable: Boolean,
    val qnnDetails: String
)

object SocDetector {

    private var cachedSocInfo: SocInfo? = null

    // Precompiled regexes to prevent loose substring misidentifications (e.g., SM-A125F, MT2100)
    private val QUALCOMM_SOC_REGEX = Regex("""\b(SM[4678][0-9]{3}[A-Z]*|SDM[678][0-9]{2}|MSM89[0-9]{2}|SC8380)\b""", RegexOption.IGNORE_CASE)
    private val QUALCOMM_HW_REGEX = Regex("""\b(QCOM|QUALCOMM|SM[0-9]{4}|SC[0-9]{4})\b""", RegexOption.IGNORE_CASE)
    private val QUALCOMM_BOARD_REGEX = Regex("""\b(KALAMA|PINEAPPLE|SUN|TARO|LAHAINA|CLIFFS|CLIFFS_PLUS|HOLI|BENGAL|KHAJE|KHATI|HAMOA|YUKON)\b""", RegexOption.IGNORE_CASE)
    private val QUALCOMM_TEXT_REGEX = Regex("""\b(QUALCOMM|SNAPDRAGON)\b""", RegexOption.IGNORE_CASE)

    private val MEDIATEK_SOC_REGEX = Regex("""\b(MT[0-9]{4}[A-Z]*|DIMENSITY|HELIO)\b""", RegexOption.IGNORE_CASE)
    private val MEDIATEK_HW_REGEX = Regex("""\b(MT[0-9]{4}[A-Z]*|MEDIATEK)\b""", RegexOption.IGNORE_CASE)
    private val MEDIATEK_TEXT_REGEX = Regex("""\b(MEDIATEK|DIMENSITY)\b""", RegexOption.IGNORE_CASE)

    private val TENSOR_SOC_REGEX = Regex("""\b(GS101|GS201|ZUMA|ZUMA_PRO)\b""", RegexOption.IGNORE_CASE)
    private val TENSOR_HW_REGEX = Regex("""\b(TENSOR|GS101|GS201|ZUMA)\b""", RegexOption.IGNORE_CASE)
    private val TENSOR_BOARD_REGEX = Regex("""\b(WHITECHAPEL|CLOUDRIPPER|RAVEN|ORIOLE|BLUEJAY|CHEETAH|PANTHER|LYNX|SHIBA|HUSKY|AKITA|TOKA|KOMODO|CAIMAN)\b""", RegexOption.IGNORE_CASE)

    private val EXYNOS_SOC_REGEX = Regex("""\b(S5E[0-9]{4}|EXYNOS[0-9]*|UNIVERSAL[0-9]*)\b""", RegexOption.IGNORE_CASE)
    private val EXYNOS_HW_REGEX = Regex("""\b(S5E[0-9]{4}|EXYNOS|UNIVERSAL[0-9]*)\b""", RegexOption.IGNORE_CASE)
    private val EXYNOS_TEXT_REGEX = Regex("""\b(EXYNOS|UNIVERSAL[0-9]{4})\b""", RegexOption.IGNORE_CASE)

    fun detectSoc(context: Context): SocInfo {
        cachedSocInfo?.let { return it }

        val rawSocModel = getRawSocModel()
        val hardware = Build.HARDWARE.orEmpty().uppercase()
        val board = Build.BOARD.orEmpty().uppercase()
        val device = Build.DEVICE.orEmpty().uppercase()
        val product = Build.PRODUCT.orEmpty().uppercase()
        val combined = "$rawSocModel|$hardware|$board|$device|$product"
        Log.i(TAG, "Hardware environment: SOC_MODEL='$rawSocModel', HARDWARE='$hardware', BOARD='$board', DEVICE='$device', PRODUCT='$product'")

        val (vendor, marketingName, htp) = classifySoc(rawSocModel, hardware, board, device, product)

        // Check availability of QNN native libraries on the device or Google Tensor TPU
        val (hasNpuRuntime, npuDetails) = checkNpuRuntime(context, vendor, htp, marketingName)

        // Only mark NPU hardware present if:
        // 1. Google Tensor (TPU is built into the Tensor platform)
        // 2. Qualcomm chip with a recognized HTP version (v68, v69, v73, v75, v79, v81)
        // 3. Or verified active QNN runtime libraries
        // Entry-level Qualcomm chips (Snapdragon 480, 680, 685, 695) have no HTP and must NOT claim NPU capability.
        val isNpuHardware = (vendor == SocVendor.GOOGLE_TENSOR) ||
            (vendor == SocVendor.QUALCOMM && htp != HtpVersion.UNKNOWN) ||
            hasNpuRuntime

        val info = SocInfo(
            vendor = vendor,
            socModel = rawSocModel.ifBlank { hardware },
            marketingName = marketingName,
            htpVersion = htp,
            isNpuHardwarePresent = isNpuHardware,
            isQnnRuntimeAvailable = hasNpuRuntime,
            qnnDetails = npuDetails
        )

        Log.i(TAG, "Detected SoC: $info")
        cachedSocInfo = info
        return info
    }

    private fun getRawSocModel(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.orEmpty().uppercase().trim()
        } else {
            ""
        }
    }

    internal fun classifySoc(
        socModel: String,
        hardware: String,
        board: String,
        device: String,
        product: String
    ): Triple<SocVendor, String, HtpVersion> {
        val combined = "$socModel|$hardware|$board|$device|$product"

        // 1. Google Tensor Detection (check first because Tensor uses custom Google boards)
        if (TENSOR_SOC_REGEX.containsMatchIn(socModel) ||
            TENSOR_HW_REGEX.containsMatchIn(hardware) ||
            TENSOR_BOARD_REGEX.containsMatchIn(board) ||
            (Build.MANUFACTURER.equals("Google", ignoreCase = true) && combined.contains("TENSOR", ignoreCase = true))
        ) {
            val name = identifyGoogleTensor(combined)
            return Triple(SocVendor.GOOGLE_TENSOR, name, HtpVersion.UNKNOWN)
        }

        // 2. Qualcomm Snapdragon Detection
        // Tightened: must match Qualcomm SoC regex, Qualcomm hardware tag, known Snapdragon board, or explicit "SNAPDRAGON" text.
        // Avoid loose substring "SM" which falsely matches Samsung model names like SM-A125F.
        val isQualcomm = QUALCOMM_SOC_REGEX.containsMatchIn(socModel) ||
            QUALCOMM_HW_REGEX.containsMatchIn(hardware) ||
            QUALCOMM_BOARD_REGEX.containsMatchIn(board) ||
            QUALCOMM_TEXT_REGEX.containsMatchIn(socModel) ||
            QUALCOMM_TEXT_REGEX.containsMatchIn(hardware)

        if (isQualcomm) {
            val (name, htp) = identifyQualcommChipset(socModel, combined)
            return Triple(SocVendor.QUALCOMM, name, htp)
        }

        // 3. MediaTek Dimensity Detection
        // Tightened: must match MTxxxx, Dimensity, or Helio patterns. Avoid loose "MT" substring.
        val isMediaTek = MEDIATEK_SOC_REGEX.containsMatchIn(socModel) ||
            MEDIATEK_HW_REGEX.containsMatchIn(hardware) ||
            MEDIATEK_TEXT_REGEX.containsMatchIn(socModel) ||
            MEDIATEK_TEXT_REGEX.containsMatchIn(hardware)

        if (isMediaTek) {
            val name = identifyMediaTekChipset(socModel, combined)
            return Triple(SocVendor.MEDIATEK, name, HtpVersion.UNKNOWN)
        }

        // 4. Samsung Exynos Detection
        // Tightened: must match S5Exxxx, Exynos, or Universal tags. Avoid loose 2100/2200/2400 substrings.
        val isExynos = EXYNOS_SOC_REGEX.containsMatchIn(socModel) ||
            EXYNOS_HW_REGEX.containsMatchIn(hardware) ||
            EXYNOS_TEXT_REGEX.containsMatchIn(socModel) ||
            EXYNOS_TEXT_REGEX.containsMatchIn(hardware)

        if (isExynos) {
            val name = identifyExynos(socModel, combined)
            return Triple(SocVendor.SAMSUNG_EXYNOS, name, HtpVersion.UNKNOWN)
        }

        Log.w(TAG, "Unrecognized SoC: rawSocModel='$socModel', hardware='$hardware', board='$board', product='$product'")
        return Triple(SocVendor.UNKNOWN, "Generic / Other ($hardware)", HtpVersion.UNKNOWN)
    }

    internal fun identifyQualcommChipset(socModel: String, combined: String): Pair<String, HtpVersion> {
        val target = "$socModel|$combined".uppercase()
        return when {
            // Snapdragon 8 Elite / Snapdragon 8 Gen 4 (SM8750 / HTP v79)
            Regex("""\b(SM8750|SUN)\b""").containsMatchIn(target) || target.contains("8 ELITE") || target.contains("8 GEN 4") ->
                Pair("Snapdragon 8 Elite (SM8750)", HtpVersion.V79)

            // Snapdragon 8s Gen 4 (SM8735 / HTP v79)
            Regex("""\b(SM8735|CLIFFS_PLUS)\b""").containsMatchIn(target) || target.contains("8S GEN 4") ->
                Pair("Snapdragon 8s Gen 4 (SM8735)", HtpVersion.V79)

            // Snapdragon 7+ Gen 4 (SM7750 / HTP v79)
            Regex("""\bSM7750\b""").containsMatchIn(target) || target.contains("7+ GEN 4") || target.contains("7PLUS GEN 4") ->
                Pair("Snapdragon 7+ Gen 4 (SM7750)", HtpVersion.V79)

            // Snapdragon 8 Gen 3 (SM8650 / HTP v75)
            Regex("""\b(SM8650|PINEAPPLE)\b""").containsMatchIn(target) || target.contains("8 GEN 3") ->
                Pair("Snapdragon 8 Gen 3 (SM8650)", HtpVersion.V75)

            // Snapdragon 8s Gen 3 (SM8635 / HTP v75)
            Regex("""\b(SM8635|CLIFFS)\b""").containsMatchIn(target) || target.contains("8S GEN 3") ->
                Pair("Snapdragon 8s Gen 3 (SM8635)", HtpVersion.V75)

            // Snapdragon 7+ Gen 3 (SM7675 / HTP v75)
            Regex("""\bSM7675\b""").containsMatchIn(target) || target.contains("7+ GEN 3") ->
                Pair("Snapdragon 7+ Gen 3 (SM7675)", HtpVersion.V75)

            // Snapdragon 8 Gen 2 (SM8550 / HTP v73)
            Regex("""\b(SM8550|KALAMA)\b""").containsMatchIn(target) || target.contains("8 GEN 2") ->
                Pair("Snapdragon 8 Gen 2 (SM8550)", HtpVersion.V73)

            // Snapdragon 7+ Gen 2 (SM7475 / HTP v73)
            Regex("""\bSM7475\b""").containsMatchIn(target) || target.contains("7+ GEN 2") ->
                Pair("Snapdragon 7+ Gen 2 (SM7475)", HtpVersion.V73)

            // Snapdragon 8+ Gen 1 (SM8475 / HTP v69)
            Regex("""\bSM8475\b""").containsMatchIn(target) || target.contains("8+ GEN 1") || target.contains("8PLUS GEN 1") ->
                Pair("Snapdragon 8+ Gen 1 (SM8475)", HtpVersion.V69)

            // Snapdragon 8 Gen 1 (SM8450 / HTP v69)
            Regex("""\b(SM8450|TARO)\b""").containsMatchIn(target) || target.contains("8 GEN 1") ->
                Pair("Snapdragon 8 Gen 1 (SM8450)", HtpVersion.V69)

            // Snapdragon 7 Gen 1 (SM7450 / HTP v69)
            Regex("""\bSM7450\b""").containsMatchIn(target) || target.contains("7 GEN 1") ->
                Pair("Snapdragon 7 Gen 1 (SM7450)", HtpVersion.V69)

            // Snapdragon 888 / 888+ (SM8350 / HTP v68)
            Regex("""\b(SM8350|LAHAINA)\b""").containsMatchIn(target) || target.contains("SNAPDRAGON 888") ->
                Pair("Snapdragon 888 (SM8350)", HtpVersion.V68)

            // Snapdragon 778G / 778G+ (SM7325 / HTP v68)
            Regex("""\b(SM7325|YUKON)\b""").containsMatchIn(target) || target.contains("778G") ->
                Pair("Snapdragon 778G (SM7325)", HtpVersion.V68)

            // Snapdragon 685 / 680 (SM6225 / Bengal / Onyx / Khaje) -> HTP UNKNOWN
            Regex("""\b(SM6225|BENGAL|ONYX|KHAJE|KHATI)\b""").containsMatchIn(target) ||
                Regex("""\bSNAPDRAGON\s*(685|680)\b""").containsMatchIn(target) ->
                Pair("Snapdragon 685 / 680 (SM6225)", HtpVersion.UNKNOWN)

            // Snapdragon 695 / 690 (SM6375 / Holi) -> HTP UNKNOWN
            Regex("""\b(SM6375|HOLI)\b""").containsMatchIn(target) ||
                Regex("""\bSNAPDRAGON\s*(695|690)\b""").containsMatchIn(target) ->
                Pair("Snapdragon 695 5G (SM6375)", HtpVersion.UNKNOWN)

            // Snapdragon 480 / 480+ (SM4350) -> HTP UNKNOWN
            Regex("""\bSM4350\b""").containsMatchIn(target) ||
                Regex("""\bSNAPDRAGON\s*480\b""").containsMatchIn(target) ->
                Pair("Snapdragon 480 (SM4350)", HtpVersion.UNKNOWN)

            // Snapdragon X Elite / Plus
            Regex("""\b(SC8380|HAMOA)\b""").containsMatchIn(target) || target.contains("X ELITE") ->
                Pair("Snapdragon X Elite", HtpVersion.V75)

            // Fallback for other Qualcomm chips
            else -> {
                val modelStr = if (socModel.isNotBlank()) socModel else "Qualcomm Snapdragon"
                Pair(modelStr, HtpVersion.UNKNOWN)
            }
        }
    }

    internal fun identifyMediaTekChipset(socModel: String, combined: String): String {
        val target = "$socModel|$combined".uppercase()
        return when {
            Regex("""\b(MT6991|DIMENSITY\s*9400)\b""").containsMatchIn(target) -> "MediaTek Dimensity 9400"
            Regex("""\b(MT6989|DIMENSITY\s*9300)\b""").containsMatchIn(target) -> "MediaTek Dimensity 9300 / 9300+"
            Regex("""\b(MT6985|DIMENSITY\s*9200)\b""").containsMatchIn(target) -> "MediaTek Dimensity 9200 / 9200+"
            Regex("""\b(MT6897|DIMENSITY\s*8300)\b""").containsMatchIn(target) -> "MediaTek Dimensity 8300 / 8300-Ultra"
            Regex("""\b(MT6895|DIMENSITY\s*8[12]00)\b""").containsMatchIn(target) -> "MediaTek Dimensity 8100 / 8200"
            Regex("""\b(MT6789|HELIO\s*G99)\b""").containsMatchIn(target) -> "MediaTek Helio G99"
            Regex("""\b(MT6785|HELIO\s*G9[056])\b""").containsMatchIn(target) -> "MediaTek Helio G90/G95"
            Regex("""\b(MT6769|HELIO\s*G8[058])\b""").containsMatchIn(target) -> "MediaTek Helio G80/G85"
            else -> if (socModel.isNotBlank()) "MediaTek $socModel" else "MediaTek Dimensity"
        }
    }

    internal fun identifyGoogleTensor(combined: String): String {
        return when {
            combined.contains("ZUMA_PRO") || combined.contains("TOKA") || combined.contains("KOMODO") || combined.contains("CAIMAN") ->
                "Google Tensor G4"
            combined.contains("ZUMA") || combined.contains("SHIBA") || combined.contains("HUSKY") || combined.contains("AKITA") ->
                "Google Tensor G3"
            combined.contains("GS201") || combined.contains("CLOUDRIPPER") || combined.contains("CHEETAH") || combined.contains("PANTHER") || combined.contains("LYNX") ->
                "Google Tensor G2"
            combined.contains("GS101") || combined.contains("WHITECHAPEL") || combined.contains("ORIOLE") || combined.contains("RAVEN") || combined.contains("BLUEJAY") ->
                "Google Tensor G1"
            else -> "Google Tensor TPU"
        }
    }

    internal fun identifyExynos(socModel: String, combined: String): String {
        val target = "$socModel|$combined".uppercase()
        return when {
            Regex("""\b(S5E9945|EXYNOS\s*2400)\b""").containsMatchIn(target) -> "Samsung Exynos 2400"
            Regex("""\b(S5E9925|EXYNOS\s*2200)\b""").containsMatchIn(target) -> "Samsung Exynos 2200"
            Regex("""\b(S5E9935|EXYNOS\s*2100)\b""").containsMatchIn(target) -> "Samsung Exynos 2100"
            Regex("""\b(S5E9815|EXYNOS\s*1080)\b""").containsMatchIn(target) -> "Samsung Exynos 1080"
            Regex("""\b(S5E8845|EXYNOS\s*1480)\b""").containsMatchIn(target) -> "Samsung Exynos 1480"
            Regex("""\b(S5E8835|EXYNOS\s*1380)\b""").containsMatchIn(target) -> "Samsung Exynos 1380"
            Regex("""\b(S5E8825|EXYNOS\s*1280)\b""").containsMatchIn(target) -> "Samsung Exynos 1280"
            else -> "Samsung Exynos"
        }
    }

    private fun checkNpuRuntime(
        context: Context,
        vendor: SocVendor,
        htpVersion: HtpVersion,
        marketingName: String = ""
    ): Pair<Boolean, String> {
        if (vendor == SocVendor.GOOGLE_TENSOR) {
            return Pair(true, "Google Tensor TPU Ready (Native LiteRT Backend)")
        }

        // QNN HTP runtime is only supported on Qualcomm chips with a known HTP version (v68-v81).
        // Chips with HtpVersion.UNKNOWN (e.g. S480, S680, S685, S695, MediaTek, Exynos) have no HTP runtime.
        if (vendor != SocVendor.QUALCOMM || htpVersion == HtpVersion.UNKNOWN) {
            val reason = if (vendor == SocVendor.QUALCOMM) {
                "Snapdragon Hexagon HTP not available on $marketingName (no HTP hardware)"
            } else {
                "NPU runtime unavailable for ${vendor.name} SoC"
            }
            return Pair(false, reason)
        }

        return try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val libDir = File(nativeLibDir)

            // Check bundled APK libs first, then check device vendor paths
            val bundledQnnHtp = libDir.exists() && File(libDir, "libQnnHtp.so").exists()
            val systemQnnHtp = File("/vendor/lib64/libQnnHtp.so").exists() || File("/system/lib64/libQnnHtp.so").exists()
            val hasQnnHtp = bundledQnnHtp || systemQnnHtp

            val bundledQnnSystem = libDir.exists() && File(libDir, "libQnnSystem.so").exists()
            val systemQnnSystem = File("/vendor/lib64/libQnnSystem.so").exists() || File("/system/lib64/libQnnSystem.so").exists()
            val hasQnnSystem = bundledQnnSystem || systemQnnSystem

            val skelLibName = when (htpVersion) {
                HtpVersion.V68 -> "libQnnHtpV68Skel.so"
                HtpVersion.V69 -> "libQnnHtpV69Skel.so"
                HtpVersion.V73 -> "libQnnHtpV73Skel.so"
                HtpVersion.V75 -> "libQnnHtpV75Skel.so"
                HtpVersion.V79 -> "libQnnHtpV79Skel.so"
                HtpVersion.V81 -> "libQnnHtpV81Skel.so"
                HtpVersion.UNKNOWN -> null
            }

            val hasExactSkel = skelLibName != null && (File(libDir, skelLibName).exists() || File("/vendor/lib64/rfsa/adsp", skelLibName).exists())
            val anySkelName = listOf(
                "libQnnHtpV81Skel.so", "libQnnHtpV79Skel.so", "libQnnHtpV75Skel.so",
                "libQnnHtpV73Skel.so", "libQnnHtpV69Skel.so", "libQnnHtpV68Skel.so"
            ).firstOrNull { File(libDir, it).exists() || File("/vendor/lib64/rfsa/adsp", it).exists() }
            val hasAnySkel = anySkelName != null

            val hasMatchingSkel = hasExactSkel || hasAnySkel

            val status = buildString {
                if (hasQnnHtp && hasQnnSystem && hasExactSkel) {
                    append("QNN HTP Runtime Ready ($skelLibName active)")
                } else if (hasQnnHtp && hasQnnSystem && hasAnySkel) {
                    append("QNN HTP Runtime Ready (Qualcomm Hexagon)")
                } else if (hasQnnHtp && hasQnnSystem) {
                    append("QNN HTP Runtime Present (Generic)")
                } else {
                    append("QNN HTP Runtime Libraries Missing")
                }
            }

            // Only mark ready if core QNN libs AND matching HTP skeleton library exist
            val isReady = hasQnnHtp && hasQnnSystem && hasMatchingSkel
            Pair(isReady, status)
        } catch (e: Exception) {
            Pair(false, "Failed to verify NPU runtime: ${e.localizedMessage}")
        }
    }

    /**
     * Determines whether the active hardware has an entry-level GPU (e.g. Adreno 610/612/616,
     * Mali-G52/G57, Snapdragon 680/685/695, Helio G series).
     * On these GPUs, Vulkan shader compilation is extremely slow (often taking 60-90s,
     * hanging the app and triggering system ANR dialogs) and native GPU drivers
     * frequently encounter out-of-memory or driver crashes.
     * In AUTO mode, routing directly to CPU (XNNPACK) delivers instant startup, 100% stability,
     * and rock-solid responsiveness.
     */
    fun shouldDefaultToCpu(context: Context): Boolean {
        val info = detectSoc(context)
        val target = "${info.socModel} ${info.marketingName} ${Build.HARDWARE} ${Build.BOARD} ${Build.DEVICE} ${Build.PRODUCT}".uppercase()

        // Qualcomm entry-level (Snapdragon 4xx, 680, 685, 695, 662, 665, Bengal, Holi, Khaje, Onyx, Tapas, Topaz)
        val qualcommLowTierRegex = Regex("""\b(SM6225|SM6375|SM4350|SM4375|SM4450|SM6115|SM6125|BENGAL|ONYX|TAPAS|TOPAZ|KHAJE|KHATI|HOLI)\b""")
        val qualcommMarketingRegex = Regex("""\bSNAPDRAGON\s*(480|680|685|695|662|665|4\s*GEN\s*1)\b""")
        val adrenoLowTierRegex = Regex("""\b(ADRENO\s*(505|506|610|612|613|615|616|618|619))\b""")
        if (qualcommLowTierRegex.containsMatchIn(target) || qualcommMarketingRegex.containsMatchIn(target) || adrenoLowTierRegex.containsMatchIn(target)) {
            return true
        }

        // MediaTek entry-level (Helio G series, MT6769, MT6785, MT6789, Dimensity 6000/700 series)
        val mediatekLowTierRegex = Regex("""\b(MT6769[A-Z0-9]*|MT6785[A-Z0-9]*|MT6789[A-Z0-9]*|MT6833[A-Z0-9]*|MT6835[A-Z0-9]*)\b""")
        val mediatekMarketingRegex = Regex("""\b(HELIO\s*G(80|85|88|90|95|96|99)|DIMENSITY\s*(6080|6100|700|720|810))\b""")
        if (mediatekLowTierRegex.containsMatchIn(target) || mediatekMarketingRegex.containsMatchIn(target)) {
            return true
        }

        return false
    }
}
