#include <cstdio>

#include "fastlane/error.h"
#include "fastlane/runtime.h"

extern "C" __attribute__((visibility("default"))) int AVmPayload_main(void) {
    try {
        fl::PayloadConfig config = fl::PayloadConfig::from_environment();
        fl::Runtime runtime(std::move(config));
        return runtime.run();
    } catch (const fl::Error& error) {
        std::fprintf(stderr, "fastlane payload failed: %s\n", fl::code_name(error.code()));
        return 1;
    } catch (const std::exception&) {
        std::fprintf(stderr, "fastlane payload failed: internal error\n");
        return 1;
    }
}

#ifndef __ANDROID__
int main() {
    return AVmPayload_main();
}
#endif
