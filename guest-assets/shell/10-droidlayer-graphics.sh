#!/bin/sh
# DroidLayer Guest Mesa 3D Graphics Environment Configuration
# Automatically configures Mesa Gallium VirGL 3D hardware acceleration
# or smoothly falls back to llvmpipe CPU rasterization if host GPU is unavailable.
# Installed into /etc/profile.d/10-droidlayer-graphics.sh

__droidlayer_probe_graphics() {
    # 1. Check if GPU acceleration was explicitly disabled via environment or kernel cmdline
    if [ "${DROIDLAYER_GPU_DISABLED:-0}" = "1" ] || [ "${VIRGL_FORCE_LLVMPIPE:-0}" = "1" ]; then
        export GALLIUM_DRIVER=llvmpipe
        export DROIDLAYER_GRAPHICS_BACKEND=llvmpipe
        unset MESA_GL_VERSION_OVERRIDE
        unset MESA_GLES_VERSION_OVERRIDE
        return 0
    fi

    if [ -r /proc/cmdline ]; then
        if grep -qs 'droidlayer.gpu=0' /proc/cmdline || grep -qs 'nomodeset' /proc/cmdline; then
            export GALLIUM_DRIVER=llvmpipe
            export DROIDLAYER_GRAPHICS_BACKEND=llvmpipe
            unset MESA_GL_VERSION_OVERRIDE
            unset MESA_GLES_VERSION_OVERRIDE
            return 0
        fi
    fi

    # 2. Probe DRM DRI device nodes
    # In VirtIO-GPU, /dev/dri/renderD128 is created when VIRTIO_GPU_F_VIRGL (3D) is negotiated.
    _has_virgl_node=0
    if [ -c /dev/dri/renderD128 ] || [ -d /sys/class/drm/renderD128 ]; then
        _has_virgl_node=1
    elif [ -c /dev/dri/card0 ] || [ -d /sys/class/drm/card0 ]; then
        # When card0 exists, verify virtio-gpu driver presence
        if [ -d /sys/bus/virtio/drivers/virtio_gpu ] || grep -qs 'virtio_gpu' /proc/modules 2>/dev/null; then
            _has_virgl_node=1
        fi
    fi

    # 3. Configure Mesa driver or fallback
    if [ "$_has_virgl_node" -eq 1 ]; then
        # VirGL 3D hardware acceleration enabled
        export GALLIUM_DRIVER=virgl
        export MESA_GL_VERSION_OVERRIDE=3.2
        export MESA_GLES_VERSION_OVERRIDE=3.2
        export DROIDLAYER_GRAPHICS_BACKEND=virgl
    else
        # Graceful fallback to llvmpipe soft rasterizer; never blocks boot
        export GALLIUM_DRIVER=llvmpipe
        export DROIDLAYER_GRAPHICS_BACKEND=llvmpipe
        unset MESA_GL_VERSION_OVERRIDE
        unset MESA_GLES_VERSION_OVERRIDE
    fi
}

__droidlayer_probe_graphics
unset -f __droidlayer_probe_graphics 2>/dev/null || true
