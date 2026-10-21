#include "fastlane/vm_payload_api.h"

extern "C" {

void AVmPayload_notifyPayloadReady(void) {}
const char* AVmPayload_getApkContentsPath(void) { return "/mnt/apk"; }
const char* AVmPayload_getEncryptedStoragePath(void) { return "/mnt/encryptedstore"; }
bool AVmPayload_isNewInstance(void) { return false; }

}  // extern "C"
