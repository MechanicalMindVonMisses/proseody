# PDFBox loads fonts, filters and encodings by name; keep it whole and let R8 trim the rest.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
-dontwarn javax.**
-dontwarn java.awt.**

# sherpa-onnx's JNI reads its Kotlin config classes by field name and calls back into GeneratedAudio.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# commons-compress optional codecs we don't use.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
