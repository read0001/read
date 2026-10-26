package com.k2fsa.sherpa.onnx

/**
 * The sherpa-onnx native library is not in the APK (it would add about 24 MB per ABI); the app downloads it on request and
 * says where it put it with [path] before the first [OfflineTts] is created.
 */
object SherpaLibrary {
    @Volatile var path: String? = null

    @Synchronized
    fun load() {
        System.load(checkNotNull(path) { "sherpa-onnx library has not been downloaded" })
    }
}
