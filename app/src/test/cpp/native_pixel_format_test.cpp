#include "../../main/cpp/graphics/pixel_format.h"
#include <stdio.h>
#include <string.h>

int main() {
    const int32_t argb[] = {static_cast<int32_t>(0xffff0000U), static_cast<int32_t>(0xff0000ffU),
                            static_cast<int32_t>(0x78123456U)};
    uint8_t actual[12] = {};
    orchestella::copyArgbToRgba(argb, actual, 3);
    const uint8_t expected[] = {255, 0, 0, 255, 0, 0, 255, 255, 0x12, 0x34, 0x56, 0x78};
    if (memcmp(actual, expected, sizeof(expected)) != 0) return 1;

    const uint8_t paddedRgba[] = {
        10, 20, 30, 255, 40, 50, 60, 255, 99, 99, 99, 99,
        70, 80, 90, 255, 100, 110, 120, 128, 88, 88, 88, 88
    };
    uint8_t packedOut[16] = {};
    orchestella::copyRgbaRows(paddedRgba, 2, 2, 12, packedOut);
    const uint8_t expectedPacked[] = {
        10, 20, 30, 255, 40, 50, 60, 255,
        70, 80, 90, 255, 100, 110, 120, 128
    };
    if (memcmp(packedOut, expectedPacked, sizeof(expectedPacked)) != 0) return 2;

    uint8_t tightOut[16] = {};
    orchestella::copyRgbaRows(expectedPacked, 2, 2, 8, tightOut);
    if (memcmp(tightOut, expectedPacked, sizeof(expectedPacked)) != 0) return 3;

    puts("ARGB_TO_RGBA_RED_BLUE_ALPHA_OK");
    return 0;
}
