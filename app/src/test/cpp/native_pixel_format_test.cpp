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
    puts("ARGB_TO_RGBA_RED_BLUE_ALPHA_OK");
    return 0;
}
