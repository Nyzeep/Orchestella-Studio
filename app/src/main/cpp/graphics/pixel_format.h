#ifndef ORCHESTELLA_PIXEL_FORMAT_H
#define ORCHESTELLA_PIXEL_FORMAT_H

#include <stddef.h>
#include <stdint.h>

namespace orchestella {

inline void copyArgbToRgba(const int32_t* argb, uint8_t* rgba, size_t pixels) {
    for (size_t index = 0; index < pixels; ++index) {
        const uint32_t color = static_cast<uint32_t>(argb[index]);
        rgba[index * 4] = static_cast<uint8_t>(color >> 16);
        rgba[index * 4 + 1] = static_cast<uint8_t>(color >> 8);
        rgba[index * 4 + 2] = static_cast<uint8_t>(color);
        rgba[index * 4 + 3] = static_cast<uint8_t>(color >> 24);
    }
}

} // namespace orchestella

#endif
