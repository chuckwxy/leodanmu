#include <jni.h>
#include <string>
#include <vector>

#include "secure_bundle.h"
#include "secure_crypto.h"
#include "secure_guard.h"

extern "C"
JNIEXPORT jint JNICALL
Java_com_github_catvod_spider_protect_ProtectedLoader_nativeCheckEnv(JNIEnv* env, jclass clazz) {
    return native_env_check();
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_github_catvod_spider_protect_ProtectedLoader_nativeDecodeBundle(
        JNIEnv* env,
        jclass clazz,
        jbyteArray stageArr,
        jbyteArray commitArr,
        jbyteArray rawShaArr,
        jobjectArray partsArr) {
    try {
        auto readByteArray = [&](jbyteArray arr) -> std::string {
            if (!arr) return "";
            jsize len = env->GetArrayLength(arr);
            std::string out(len, '\0');
            env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(&out[0]));
            return out;
        };

        std::string stage = readByteArray(stageArr);
        std::string commit = readByteArray(commitArr);
        std::string rawSha = readByteArray(rawShaArr);

        jsize count = env->GetArrayLength(partsArr);
        std::vector<std::vector<uint8_t>> parts;
        parts.reserve(count);
        for (jsize i = 0; i < count; ++i) {
            auto part = static_cast<jbyteArray>(env->GetObjectArrayElement(partsArr, i));
            jsize len = env->GetArrayLength(part);
            std::vector<uint8_t> buf(len);
            env->GetByteArrayRegion(part, 0, len, reinterpret_cast<jbyte*>(buf.data()));
            parts.push_back(std::move(buf));
            env->DeleteLocalRef(part);
        }

        auto merged = merge_bundle_parts(parts);
        auto decoded = decode_native_bundle(stage, commit, rawSha, merged);
        jbyteArray out = env->NewByteArray(static_cast<jsize>(decoded.size()));
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(decoded.size()), reinterpret_cast<const jbyte*>(decoded.data()));
        return out;
    } catch (...) {
        return nullptr;
    }
}
