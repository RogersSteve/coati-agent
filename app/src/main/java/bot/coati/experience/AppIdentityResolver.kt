package bot.coati.experience

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest
import java.util.Locale

class AppIdentityResolver(private val context: Context) {
    fun resolve(packageName: String): ManualAppIdentity? {
        if (packageName.isBlank() || packageName == "unknown") return null
        return runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
            val signers = packageInfo.signingInfo?.let { signingInfo ->
                if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            }.orEmpty()
            val signerDigest = signers
                .map { sha256(it.toByteArray()) }
                .sorted()
                .joinToString(":")
                .ifBlank { "unsigned" }
            val appKey = sha256("$packageName|$signerDigest".toByteArray(Charsets.UTF_8))
            val label = packageInfo.applicationInfo?.loadLabel(context.packageManager)?.toString().orEmpty()
                .replace(Regex("[\\p{Cc}<>{}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(80)
            val versionCode = packageInfo.longVersionCode
            val locale = context.resources.configuration.locales.get(0)?.toLanguageTag()
                ?: Locale.getDefault().toLanguageTag()
            ManualAppIdentity(
                appKey = appKey,
                packageName = packageName,
                signerSha256 = signerDigest,
                displayName = label.ifBlank { packageName },
                versionCode = versionCode,
                localeTag = locale,
            )
        }.getOrNull()
    }

    companion object {
        internal fun sha256(bytes: ByteArray): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
    }
}
