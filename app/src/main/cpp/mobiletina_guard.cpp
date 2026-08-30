#include <jni.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "native_token.h"

namespace {

constexpr size_t kDigestSize = 32;

static const uint8_t kTextDigest[kDigestSize] = {
    0x21, 0xc5, 0x8f, 0xb7, 0x09, 0xfa, 0x97, 0x01,
    0xfa, 0x8c, 0xf2, 0x59, 0xcf, 0x8a, 0xc3, 0xad,
    0xda, 0xf0, 0xc8, 0xfc, 0x9d, 0x28, 0x7a, 0x51,
    0xe5, 0xa9, 0x1a, 0x2a, 0x01, 0xa8, 0x23, 0xb1,
};

static const volatile uint8_t kMask[kDigestSize] = {
    0x6d, 0x13, 0xa7, 0x4c, 0xf2, 0x89, 0x35, 0xde,
    0x51, 0xb8, 0x0f, 0xc3, 0x7a, 0xe6, 0x24, 0x95,
    0xd1, 0x48, 0xbb, 0x02, 0x6f, 0xac, 0x73, 0xe0,
    0x19, 0x84, 0xd7, 0x3e, 0xa1, 0x5b, 0xc9, 0x26,
};

[[noreturn]] void stop() {
    abort();
}

void check(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        stop();
    }
}

bool equal(const uint8_t* left, const uint8_t* right, size_t size) {
    uint8_t difference = 0;
    for (size_t index = 0; index < size; ++index) {
        difference |= static_cast<uint8_t>(left[index] ^ right[index]);
    }
    return difference == 0;
}

uint32_t rotate_right(uint32_t value, uint32_t shift) {
    return (value >> shift) | (value << (32U - shift));
}

struct Sha256 {
    uint8_t data[64];
    uint32_t data_length;
    uint64_t bit_length;
    uint32_t state[8];
};

static const uint32_t kSha256Round[64] = {
    0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
    0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
    0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
    0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
    0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
    0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
    0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
    0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
    0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
    0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
    0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
    0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
    0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
    0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
    0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
    0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U,
};

void sha256_transform(Sha256* context, const uint8_t data[64]) {
    uint32_t words[64];
    for (uint32_t index = 0; index < 16; ++index) {
        const uint32_t offset = index * 4;
        words[index] = (static_cast<uint32_t>(data[offset]) << 24U) |
            (static_cast<uint32_t>(data[offset + 1]) << 16U) |
            (static_cast<uint32_t>(data[offset + 2]) << 8U) |
            static_cast<uint32_t>(data[offset + 3]);
    }
    for (uint32_t index = 16; index < 64; ++index) {
        const uint32_t first = rotate_right(words[index - 15], 7U) ^
            rotate_right(words[index - 15], 18U) ^ (words[index - 15] >> 3U);
        const uint32_t second = rotate_right(words[index - 2], 17U) ^
            rotate_right(words[index - 2], 19U) ^ (words[index - 2] >> 10U);
        words[index] = words[index - 16] + first + words[index - 7] + second;
    }

    uint32_t a = context->state[0];
    uint32_t b = context->state[1];
    uint32_t c = context->state[2];
    uint32_t d = context->state[3];
    uint32_t e = context->state[4];
    uint32_t f = context->state[5];
    uint32_t g = context->state[6];
    uint32_t h = context->state[7];

    for (uint32_t index = 0; index < 64; ++index) {
        const uint32_t sigma_one = rotate_right(e, 6U) ^ rotate_right(e, 11U) ^
            rotate_right(e, 25U);
        const uint32_t choose = (e & f) ^ ((~e) & g);
        const uint32_t first = h + sigma_one + choose + kSha256Round[index] + words[index];
        const uint32_t sigma_zero = rotate_right(a, 2U) ^ rotate_right(a, 13U) ^
            rotate_right(a, 22U);
        const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
        const uint32_t second = sigma_zero + majority;

        h = g;
        g = f;
        f = e;
        e = d + first;
        d = c;
        c = b;
        b = a;
        a = first + second;
    }

    context->state[0] += a;
    context->state[1] += b;
    context->state[2] += c;
    context->state[3] += d;
    context->state[4] += e;
    context->state[5] += f;
    context->state[6] += g;
    context->state[7] += h;
}

void sha256(const uint8_t* input, size_t size, uint8_t output[kDigestSize]) {
    Sha256 context = {
        {},
        0,
        0,
        {
            0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
            0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U,
        },
    };

    for (size_t index = 0; index < size; ++index) {
        context.data[context.data_length++] = input[index];
        if (context.data_length == 64) {
            sha256_transform(&context, context.data);
            context.bit_length += 512;
            context.data_length = 0;
        }
    }

    uint32_t index = context.data_length;
    context.data[index++] = 0x80;
    if (index > 56) {
        while (index < 64) context.data[index++] = 0;
        sha256_transform(&context, context.data);
        index = 0;
    }
    while (index < 56) context.data[index++] = 0;

    context.bit_length += static_cast<uint64_t>(context.data_length) * 8U;
    for (uint32_t offset = 0; offset < 8; ++offset) {
        context.data[63U - offset] =
            static_cast<uint8_t>(context.bit_length >> (offset * 8U));
    }
    sha256_transform(&context, context.data);

    for (uint32_t word = 0; word < 8; ++word) {
        output[word * 4] = static_cast<uint8_t>(context.state[word] >> 24U);
        output[word * 4 + 1] = static_cast<uint8_t>(context.state[word] >> 16U);
        output[word * 4 + 2] = static_cast<uint8_t>(context.state[word] >> 8U);
        output[word * 4 + 3] = static_cast<uint8_t>(context.state[word]);
    }
}

void reject_tracer() {
    FILE* status = fopen("/proc/self/status", "r");
    if (status == nullptr) return;

    char line[160];
    long tracer = 0;
    while (fgets(line, sizeof(line), status) != nullptr) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            tracer = strtol(line + 10, nullptr, 10);
            break;
        }
    }
    fclose(status);
    if (tracer != 0) stop();
}

void expected_signer(uint8_t output[kDigestSize]) {
    for (size_t index = 0; index < kDigestSize; ++index) {
        output[index] = static_cast<uint8_t>(
            kToken[index] ^ kMask[index] ^ kTextDigest[index]
        );
    }
}

void signer_from_package(JNIEnv* env, jobject context, uint8_t output[kDigestSize]) {
    jclass context_class = env->GetObjectClass(context);
    check(env);
    if (context_class == nullptr) stop();

    jmethodID get_package_name = env->GetMethodID(
        context_class, "getPackageName", "()Ljava/lang/String;"
    );
    jmethodID get_package_manager = env->GetMethodID(
        context_class, "getPackageManager", "()Landroid/content/pm/PackageManager;"
    );
    check(env);
    if (get_package_name == nullptr || get_package_manager == nullptr) stop();

    jstring package_name = static_cast<jstring>(env->CallObjectMethod(context, get_package_name));
    jobject package_manager = env->CallObjectMethod(context, get_package_manager);
    check(env);
    if (package_name == nullptr || package_manager == nullptr) stop();

    jclass version_class = env->FindClass("android/os/Build$VERSION");
    check(env);
    if (version_class == nullptr) stop();
    jfieldID sdk_field = env->GetStaticFieldID(version_class, "SDK_INT", "I");
    check(env);
    if (sdk_field == nullptr) stop();
    const jint sdk = env->GetStaticIntField(version_class, sdk_field);

    jclass manager_class = env->GetObjectClass(package_manager);
    jmethodID get_package_info = env->GetMethodID(
        manager_class,
        "getPackageInfo",
        "(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;"
    );
    check(env);
    if (get_package_info == nullptr) stop();

    const jint flags = sdk >= 28 ? 0x08000000 : 0x00000040;
    jobject package_info = env->CallObjectMethod(
        package_manager, get_package_info, package_name, flags
    );
    check(env);
    if (package_info == nullptr) stop();

    jclass package_info_class = env->GetObjectClass(package_info);
    jobjectArray signatures = nullptr;
    if (sdk >= 28) {
        jfieldID signing_info_field = env->GetFieldID(
            package_info_class,
            "signingInfo",
            "Landroid/content/pm/SigningInfo;"
        );
        check(env);
        if (signing_info_field == nullptr) stop();
        jobject signing_info = env->GetObjectField(package_info, signing_info_field);
        check(env);
        if (signing_info == nullptr) stop();
        jclass signing_info_class = env->GetObjectClass(signing_info);
        jmethodID get_signers = env->GetMethodID(
            signing_info_class,
            "getApkContentsSigners",
            "()[Landroid/content/pm/Signature;"
        );
        check(env);
        if (get_signers == nullptr) stop();
        signatures = static_cast<jobjectArray>(
            env->CallObjectMethod(signing_info, get_signers)
        );
    } else {
        jfieldID signatures_field = env->GetFieldID(
            package_info_class,
            "signatures",
            "[Landroid/content/pm/Signature;"
        );
        check(env);
        if (signatures_field == nullptr) stop();
        signatures = static_cast<jobjectArray>(
            env->GetObjectField(package_info, signatures_field)
        );
    }
    check(env);
    if (signatures == nullptr || env->GetArrayLength(signatures) != 1) stop();

    jobject signature = env->GetObjectArrayElement(signatures, 0);
    check(env);
    if (signature == nullptr) stop();
    jclass signature_class = env->GetObjectClass(signature);
    jmethodID to_byte_array = env->GetMethodID(signature_class, "toByteArray", "()[B");
    check(env);
    if (to_byte_array == nullptr) stop();
    jbyteArray encoded = static_cast<jbyteArray>(
        env->CallObjectMethod(signature, to_byte_array)
    );
    check(env);
    if (encoded == nullptr) stop();

    const jsize encoded_size = env->GetArrayLength(encoded);
    if (encoded_size <= 0) stop();
    uint8_t* certificate = static_cast<uint8_t*>(malloc(static_cast<size_t>(encoded_size)));
    if (certificate == nullptr) stop();
    env->GetByteArrayRegion(
        encoded,
        0,
        encoded_size,
        reinterpret_cast<jbyte*>(certificate)
    );
    check(env);
    sha256(certificate, static_cast<size_t>(encoded_size), output);
    memset(certificate, 0, static_cast<size_t>(encoded_size));
    free(certificate);
}

void verify_signer(JNIEnv* env, jobject context, uint8_t actual[kDigestSize]) {
    reject_tracer();
    signer_from_package(env, context, actual);
    uint8_t expected[kDigestSize];
    expected_signer(expected);
    if (!equal(actual, expected, kDigestSize)) stop();
    memset(expected, 0, sizeof(expected));
}

uint64_t rotate_left(uint64_t value, uint32_t shift) {
    return (value << shift) | (value >> (64U - shift));
}

uint64_t proof(
    const uint8_t signer[kDigestSize],
    const uint8_t text[kDigestSize],
    uint64_t nonce
) {
    uint64_t state = nonce ^ UINT64_C(0x9e3779b97f4a7c15);
    for (size_t index = 0; index < kDigestSize; ++index) {
        state = rotate_left(state ^ signer[index], 11U) * UINT64_C(0x100000001b3);
        state ^= state >> 29U;
    }
    for (size_t index = 0; index < kDigestSize; ++index) {
        state = rotate_left(state ^ text[index], 7U) * UINT64_C(0x100000001b3);
        state ^= state >> 31U;
    }
    return state ^ UINT64_C(0xd6e8feb86659fd93);
}

void native_a(JNIEnv* env, jobject, jobject context) {
    if (context == nullptr) stop();
    uint8_t actual[kDigestSize];
    verify_signer(env, context, actual);
    memset(actual, 0, sizeof(actual));
}

jlong native_b(
    JNIEnv* env,
    jobject,
    jobject context,
    jbyteArray signer_digest,
    jbyteArray text_digest,
    jlong nonce
) {
    if (context == nullptr || signer_digest == nullptr || text_digest == nullptr) stop();
    if (env->GetArrayLength(signer_digest) != static_cast<jsize>(kDigestSize) ||
        env->GetArrayLength(text_digest) != static_cast<jsize>(kDigestSize)) {
        stop();
    }

    uint8_t signer[kDigestSize];
    uint8_t text[kDigestSize];
    env->GetByteArrayRegion(
        signer_digest, 0, static_cast<jsize>(kDigestSize), reinterpret_cast<jbyte*>(signer)
    );
    env->GetByteArrayRegion(
        text_digest, 0, static_cast<jsize>(kDigestSize), reinterpret_cast<jbyte*>(text)
    );
    check(env);

    uint8_t actual[kDigestSize];
    verify_signer(env, context, actual);
    if (!equal(signer, actual, kDigestSize) || !equal(text, kTextDigest, kDigestSize)) stop();

    const uint64_t result = proof(actual, text, static_cast<uint64_t>(nonce));
    memset(actual, 0, sizeof(actual));
    memset(signer, 0, sizeof(signer));
    memset(text, 0, sizeof(text));
    return static_cast<jlong>(result);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass bridge = env->FindClass("io/nekohasekai/sagernet/utils/N");
    if (bridge == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return JNI_ERR;
    }

    const JNINativeMethod methods[] = {
        {
            const_cast<char*>("a"),
            const_cast<char*>("(Landroid/content/Context;)V"),
            reinterpret_cast<void*>(native_a),
        },
        {
            const_cast<char*>("b"),
            const_cast<char*>("(Landroid/content/Context;[B[BJ)J"),
            reinterpret_cast<void*>(native_b),
        },
    };
    if (env->RegisterNatives(bridge, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        env->ExceptionClear();
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
