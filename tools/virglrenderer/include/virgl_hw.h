/*
 * Copyright (C) 2014 Red Hat Inc.
 * Copyright (C) 2026 Orchestella Studio
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 * THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

#ifndef VIRGL_HW_H
#define VIRGL_HW_H

#include <stdint.h>

#define VIRGL_RES_BIND_DEPTH_STENCIL (1 << 0)
#define VIRGL_RES_BIND_RENDER_TARGET (1 << 1)
#define VIRGL_RES_BIND_SAMPLER_VIEW  (1 << 3)
#define VIRGL_RES_BIND_VERTEX_BUFFER (1 << 4)
#define VIRGL_RES_BIND_INDEX_BUFFER  (1 << 5)
#define VIRGL_RES_BIND_CONSTANT_BUFFER (1 << 6)
#define VIRGL_RES_BIND_DISPLAY_TARGET (1 << 7)
#define VIRGL_RES_BIND_STREAM_OUTPUT (1 << 11)
#define VIRGL_RES_BIND_CURSOR        (1 << 16)
#define VIRGL_RES_BIND_CUSTOM        (1 << 17)
#define VIRGL_RES_BIND_SCANOUT       (1 << 18)

enum virgl_formats {
   VIRGL_FORMAT_NONE                     = 0,
   VIRGL_FORMAT_B8G8R8A8_UNORM           = 1,
   VIRGL_FORMAT_B8G8R8X8_UNORM           = 2,
   VIRGL_FORMAT_A8R8G8B8_UNORM           = 3,
   VIRGL_FORMAT_X8R8G8B8_UNORM           = 4,
   VIRGL_FORMAT_B5G5R5A1_UNORM           = 5,
   VIRGL_FORMAT_B4G4R4A4_UNORM           = 6,
   VIRGL_FORMAT_B5G6R5_UNORM             = 7,
   VIRGL_FORMAT_R16_UNORM                = 8,
   VIRGL_FORMAT_R16G16_UNORM             = 9,
   VIRGL_FORMAT_R16G16B16A16_UNORM       = 10,
   VIRGL_FORMAT_R8_UNORM                 = 64,
   VIRGL_FORMAT_R8G8_UNORM               = 65,
   VIRGL_FORMAT_R8G8B8_UNORM             = 66,
   VIRGL_FORMAT_R8G8B8A8_UNORM           = 67,
   VIRGL_FORMAT_R8G8B8X8_UNORM           = 68,
   VIRGL_FORMAT_S8_UINT_Z24_UNORM        = 134,
   VIRGL_FORMAT_Z24_UNORM_S8_UINT        = 135,
   VIRGL_FORMAT_MAX,
};

enum virgl_cmd {
   VIRGL_CCMD_NOP                        = 0,
   VIRGL_CCMD_CREATE_OBJECT              = 1,
   VIRGL_CCMD_BIND_OBJECT                = 2,
   VIRGL_CCMD_DESTROY_OBJECT             = 3,
   VIRGL_CCMD_SET_VIEWPORT_STATE         = 4,
   VIRGL_CCMD_SET_FRAMEBUFFER_STATE      = 5,
   VIRGL_CCMD_SET_VERTEX_BUFFERS         = 6,
   VIRGL_CCMD_CLEAR                      = 7,
   VIRGL_CCMD_DRAW_VBO                   = 8,
   VIRGL_CCMD_RESOURCE_INLINE_WRITE      = 9,
   VIRGL_CCMD_SET_SAMPLER_VIEWS          = 10,
   VIRGL_CCMD_SET_INDEX_BUFFER           = 11,
   VIRGL_CCMD_SET_CONSTANT_BUFFER        = 12,
   VIRGL_CCMD_SET_STENCIL_REF            = 13,
   VIRGL_CCMD_SURFACE_INLINE_WRITE       = 14,
   VIRGL_CCMD_SET_BLEND_COLOR            = 15,
   VIRGL_CCMD_SET_SCISSOR_STATE          = 16,
   VIRGL_CCMD_END_QUERY                  = 17,
   VIRGL_CCMD_BEGIN_QUERY                = 18,
   VIRGL_CCMD_SET_POLYGON_STIPPLE        = 19,
   VIRGL_CCMD_SET_CLIP_STATE             = 20,
   VIRGL_CCMD_SET_SAMPLE_MASK            = 21,
   VIRGL_CCMD_SET_STREAMOUT_TARGETS      = 22,
   VIRGL_CCMD_SET_RENDER_CONDITION       = 23,
   VIRGL_CCMD_SET_UNIFORM_BUFFER         = 24,
   VIRGL_CCMD_SET_SUB_CTX                = 25,
   VIRGL_CCMD_CREATE_SUB_CTX             = 26,
   VIRGL_CCMD_DESTROY_SUB_CTX            = 27,
   VIRGL_CCMD_BIND_SHADER                = 28,
   VIRGL_CCMD_MAX_COMMANDS
};

#endif /* VIRGL_HW_H */
