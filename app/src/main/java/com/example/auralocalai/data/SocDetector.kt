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

    fun detectSoc(context: Context): SocInfo {
        cachedSocInfo?.let { return it }

        val rawSocModel = getRawSocModel()
        val hardware = Build.HARDWARE.orEmpty().uppercase()
        val board = Build.BOARD.orEmpty().uppercase()
        val combined = "$rawSocModel|$hardware|$board"

        val (vendor, marketingName, htp) = classifySoc(rawSocModel, hardware, board, combined)
        val isNpuHardware = vendor == SocVendor.QUALCOMM && htp != HtpVersion.UNKNOWN

        // Check availability of QNN native libraries on the device
        val (hasQnnRuntime, qnnDetails) = checkQnnRuntime(context, htp)

        val info = SocInfo(
            vendor = vendor,
            socModel = rawSocModel.ifBlank { hardware },
            marketingName = marketingName,
            htpVersion = htp,
            isNpuHardwarePresent = isNpuHardware,
            isQnnRuntimeAvailable = hasQnnRuntime,
            qnnDetails = qnnDetails
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

    private fun classifySoc(
        socModel: String,
        hardware: String,
        board: String,
        combined: String
    ): Triple<SocVendor, String, HtpVersion> {
        // 1. Qualcomm Snapdragon Detection
        if (combined.contains("QCOM") || combined.contains("QUALCOMM") || combined.contains("SM") ||
            combined.contains("SNAPDRAGON") || socModel.startsWith("SM") || hardware.startsWith("QCOM")
        ) {
            val (name, htp) = identifyQualcommChipset(socModel, combined)
            return Triple(SocVendor.QUALCOMM, name, htp)
        }

        // 2. MediaTek Dimensity Detection
        if (combined.contains("MT") || combined.contains("MEDIATEK") || combined.contains("DIMENSITY")) {
            val name = identifyMediaTekChipset(socModel, combined)
            return Triple(SocVendor.MEDIATEK, name, HtpVersion.UNKNOWN)
        }

        // 3. Google Tensor Detection
        if (combined.contains("GS101") || combined.contains("GS201") || combined.contains("ZUMA") ||
            combined.contains("CLOUDRIPPER") || combined.contains("TENSOR") || board.contains("CHEETAH") ||
            board.contains("PANTHER") || board.contains("SHIBA") || board.contains("HUSKY") ||
            board.contains("TOKA") || board.contains("KOMODO") || board.contains("CAIMAN")
        ) {
            val name = identifyGoogleTensor(combined)
            return Triple(SocVendor.GOOGLE_TENSOR, name, HtpVersion.UNKNOWN)
        }

        // 4. Samsung Exynos Detection
        if (combined.contains("EXYNOS") || combined.contains("UNIVERSAL") || combined.contains("S5E")) {
            val name = identifyExynos(combined)
            return Triple(SocVendor.SAMSUNG_EXYNOS, name, HtpVersion.UNKNOWN)
        }

        return Triple(SocVendor.UNKNOWN, "Generic / Other ($hardware)", HtpVersion.UNKNOWN)
    }

    private fun identifyQualcommChipset(socModel: String, combined: String): Pair<String, HtpVersion> {
        return when {
            // Snapdragon 8 Elite / Snapdragon 8 Gen 4 (SM8750 / HTP v79)
            socModel.contains("SM8750") || combined.contains("SM8750") || combined.contains("SUN") ||
                combined.contains("8 GEN 4") || combined.contains("8GEN4") || combined.contains("8 ELITE") ->
                Pair("Snapdragon 8 Elite (SM8750)", HtpVersion.V79)

            // Snapdragon 8s Gen 4 (SM8735 / HTP v79)
            socModel.contains("SM8735") || combined.contains("SM8735") ||
                combined.contains("8S GEN 4") || combined.contains("8SGEN4") || combined.contains("CLIFFS_PLUS") ->
                Pair("Snapdragon 8s Gen 4 (SM8735)", HtpVersion.V79)

            // Snapdragon 7+ Gen 4 (SM7750 / HTP v79)
            socModel.contains("SM7750") || combined.contains("SM7750") ||
                combined.contains("7+ GEN 4") || combined.contains("7PLUS GEN 4") ->
                Pair("Snapdragon 7+ Gen 4 (SM7750)", HtpVersion.V79)

            // Snapdragon 8 Gen 3 (SM8650 / HTP v75)
            socModel.contains("SM8650") || combined.contains("SM8650") || combined.contains("PINEAPPLE") ->
                Pair("Snapdragon 8 Gen 3 (SM8650)", HtpVersion.V75)

            // Snapdragon 8s Gen 3 (SM8635 / HTP v75)
            socModel.contains("SM8635") || combined.contains("SM8635") || combined.contains("CLIFFS") ->
                Pair("Snapdragon 8s Gen 3 (SM8635)", HtpVersion.V75)

            // Snapdragon 7+ Gen 3 (SM7675 / HTP v75)
            socModel.contains("SM7675") || combined.contains("SM7675") ->
                Pair("Snapdragon 7+ Gen 3 (SM7675)", HtpVersion.V75)

            // Snapdragon 8 Gen 2 (SM8550 / HTP v73)
            socModel.contains("SM8550") || combined.contains("SM8550") || combined.contains("KALAMA") ->
                Pair("Snapdragon 8 Gen 2 (SM8550)", HtpVersion.V73)

            // Snapdragon 7+ Gen 2 (SM7475 / HTP v73)
            socModel.contains("SM7475") || combined.contains("SM7475") ->
                Pair("Snapdragon 7+ Gen 2 (SM7475)", HtpVersion.V73)

            // Snapdragon 8+ Gen 1 (SM8475 / HTP v69)
            socModel.contains("SM8475") || combined.contains("SM8475") ->
                Pair("Snapdragon 8+ Gen 1 (SM8475)", HtpVersion.V69)

            // Snapdragon 8 Gen 1 (SM8450 / HTP v69)
            socModel.contains("SM8450") || combined.contains("SM8450") || combined.contains("TARO") ->
                Pair("Snapdragon 8 Gen 1 (SM8450)", HtpVersion.V69)

            // Snapdragon 7 Gen 1 (SM7450 / HTP v69)
            socModel.contains("SM7450") || combined.contains("SM7450") ->
                Pair("Snapdragon 7 Gen 1 (SM7450)", HtpVersion.V69)

            // Snapdragon 888 / 888+ (SM8350 / HTP v68)
            socModel.contains("SM8350") || combined.contains("SM8350") || combined.contains("LAHAINA") ->
                Pair("Snapdragon 888 (SM8350)", HtpVersion.V68)

            // Snapdragon 778G / 778G+ (SM7325 / HTP v68)
            socModel.contains("SM7325") || combined.contains("SM7325") || combined.contains("YUKON") ->
                Pair("Snapdragon 778G (SM7325)", HtpVersion.V68)

            // Snapdragon X Elite / Plus
            combined.contains("SC8380") || combined.contains("HAMOA") ->
                Pair("Snapdragon X Elite", HtpVersion.V75)

            // Fallback for other Qualcomm chips
            else -> {
                val modelStr = if (socModel.isNotBlank()) socModel else "Qualcomm Snapdragon"
                Pair(modelStr, HtpVersion.UNKNOWN)
            }
        }
    }

    private fun identifyMediaTekChipset(socModel: String, combined: String): String {
        return when {
            socModel.contains("MT6991") || combined.contains("MT6991") -> "MediaTek Dimensity 9400"
            socModel.contains("MT6989") || combined.contains("MT6989") -> "MediaTek Dimensity 9300 / 9300+"
            socModel.contains("MT6985") || combined.contains("MT6985") -> "MediaTek Dimensity 9200 / 9200+"
            socModel.contains("MT6897") || combined.contains("MT6897") -> "MediaTek Dimensity 8300 / 8300-Ultra"
            socModel.contains("MT6895") || combined.contains("MT6895") -> "MediaTek Dimensity 8100 / 8200"
            else -> if (socModel.isNotBlank()) "MediaTek $socModel" else "MediaTek Dimensity"
        }
    }

    private fun identifyGoogleTensor(combined: String): String {
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

    private fun identifyExynos(combined: String): String {
        return when {
            combined.contains("S5E9945") || combined.contains("2400") -> "Samsung Exynos 2400"
            combined.contains("S5E9925") || combined.contains("2200") -> "Samsung Exynos 2200"
            combined.contains("S5E9815") || combined.contains("1080") -> "Samsung Exynos 1080"
            combined.contains("S5E9935") || combined.contains("2100") -> "Samsung Exynos 2100"
            else -> "Samsung Exynos NPU"
        }
    }

    private fun checkQnnRuntime(context: Context, htpVersion: HtpVersion): Pair<Boolean, String> {
        return try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val libDir = File(nativeLibDir)
            if (!libDir.exists() || !libDir.isDirectory) {
                return Pair(false, "Native library directory not found")
            }

            val hasQnnHtp = File(libDir, "libQnnHtp.so").exists()
            val hasQnnSystem = File(libDir, "libQnnSystem.so").exists()
            val hasQnnDelegate = File(libDir, "libqnn_delegate_jni.so").exists() || File(libDir, "libQnnTFLiteDelegate.so").exists()

            // Check if specific HTP skeleton library is available
            val skelLibName = when (htpVersion) {
                HtpVersion.V68 -> "libQnnHtpV68Skel.so"
                HtpVersion.V69 -> "libQnnHtpV69Skel.so"
                HtpVersion.V73 -> "libQnnHtpV73Skel.so"
                HtpVersion.V75 -> "libQnnHtpV75Skel.so"
                HtpVersion.V79 -> "libQnnHtpV79Skel.so"
                HtpVersion.V81 -> "libQnnHtpV81Skel.so"
                HtpVersion.UNKNOWN -> null
            }
            val hasMatchingSkel = skelLibName != null && File(libDir, skelLibName).exists()

            val status = buildString {
                if (hasQnnHtp && hasQnnSystem && hasMatchingSkel) {
                    append("QNN HTP Runtime Ready ($skelLibName active)")
                } else if (hasQnnHtp && hasQnnSystem) {
                    append("QNN HTP Runtime Present (Generic)")
                } else {
                    append("QNN Runtime Libraries Missing")
                }
            }

            val isReady = hasQnnHtp && hasQnnSystem && hasMatchingSkel
            Pair(isReady, status)
        } catch (e: Exception) {
            Pair(false, "Failed to verify QNN runtime: ${e.localizedMessage}")
        }
    }
}
