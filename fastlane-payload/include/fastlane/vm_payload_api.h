#pragma once

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * The subset of the AOSP libvm_payload API used by this payload. The names and
 * semantics are from packages/modules/Virtualization/libs/libvm_payload
 * (Apache-2.0). This header is a local declaration only; on the device the
 * symbols resolve to the platform libvm_payload.so.
 */
void AVmPayload_notifyPayloadReady(void);
const char* AVmPayload_getApkContentsPath(void);
const char* AVmPayload_getEncryptedStoragePath(void);
bool AVmPayload_isNewInstance(void);

#ifdef __cplusplus
}
#endif
