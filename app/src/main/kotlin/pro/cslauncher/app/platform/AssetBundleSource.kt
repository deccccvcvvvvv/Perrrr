package pro.cslauncher.app.platform

import android.content.res.AssetManager
import pro.cslauncher.core.runtime.AutoProvisioner
import pro.cslauncher.core.runtime.BundleIndex
import pro.cslauncher.core.runtime.BundleSource
import java.io.FileNotFoundException
import java.io.InputStream

/** Reads the runtimes/components the CI build packed into assets/bundled/<abi>/. */
class AssetBundleSource(private val assets: AssetManager) : BundleSource {
    private val cache = HashMap<String, BundleIndex?>()
    @Synchronized override fun index(abi: String): BundleIndex? = cache.getOrPut(abi) {
        try { assets.open("bundled/$abi/index.json").bufferedReader().use { AutoProvisioner.parseIndex(it.readText()) } }
        catch (e: FileNotFoundException) { null }
    }
    override fun open(abi: String, file: String): InputStream {
        require(!file.contains("..") && !file.startsWith("/")) { "Bad bundled path $file" }
        return assets.open("bundled/$abi/$file", AssetManager.ACCESS_STREAMING)
    }
}
