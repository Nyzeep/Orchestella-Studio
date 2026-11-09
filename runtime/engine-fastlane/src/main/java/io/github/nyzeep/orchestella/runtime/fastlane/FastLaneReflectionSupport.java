package io.github.nyzeep.orchestella.runtime.fastlane;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;

/**
 * Reflective invoke primitives and platform-failure mapping for
 * {@link ReflectiveFastLaneApi}, extracted verbatim (2026-11-09 round A,
 * seam 2) so the hidden-API boundary semantics and the FastLaneException
 * reason mapping evolve in one focused place.
 *
 * <p>Package-private on purpose: the adapter surface stays
 * {@link ReflectiveFastLaneApi}/{@link ReflectiveFastLaneApi.Shape}; nothing
 * here changes the public API or any failure mapping.
 */
final class FastLaneReflectionSupport {

    private FastLaneReflectionSupport() {
    }

    static void set(Class<?> type, Object target, String method, Class<?> param,
            Object value, List<String> notes) throws FastLaneException {
        invoke(type, target, method, new Class<?>[] {param}, new Object[] {value},
                FastLaneException.VM_CREATE_FAILED, notes);
    }

    static Object invoke(Class<?> type, Object target, String name, Class<?>[] params,
            Object[] args, String reason, List<String> notes) throws FastLaneException {
        try {
            Object value = type.getMethod(name, params).invoke(target, args);
            notes.add(name + ": applied");
            return value;
        } catch (InvocationTargetException failure) {
            throw mapped(name, reason, failure.getCause());
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "required AVF method inaccessible: " + name, failure);
        } catch (SecurityException | IllegalArgumentException failure) {
            throw error(FastLaneException.HIDDEN_API_BLOCKED,
                    "required AVF method rejected: " + name, failure);
        }
    }

    static FastLaneException mapped(String stage, String reason, Throwable cause) {
        Throwable boundary = unwrapIoBoundary(cause);
        String mappedReason = reason;
        if (boundary instanceof SecurityException) {
            mappedReason = FastLaneException.PERMISSION_DENIED;
        } else if (boundary instanceof UnsupportedOperationException) {
            mappedReason = FastLaneException.PROTECTED_UNAVAILABLE;
        } else if (boundary instanceof ReflectiveOperationException
                || boundary instanceof LinkageError) {
            mappedReason = FastLaneException.HIDDEN_API_BLOCKED;
        } else if (looksLikePayloadVerificationFailure(boundary)) {
            mappedReason = FastLaneException.PAYLOAD_VERIFY_FAILED;
        }
        return error(mappedReason, "Fast Lane operation failed: " + stage, cause);
    }

    /**
     * A descriptor-adoption failure wraps the real boundary exception in an
     * IOException. A platform VirtualMachineException keeps its own identity so
     * payload verification is not mistaken for a transport error.
     */
    static Throwable unwrapIoBoundary(Throwable cause) {
        if (cause instanceof IOException && cause.getCause() != null
                && !isVirtualMachineException(cause.getCause())) {
            return cause.getCause();
        }
        return cause;
    }

    static boolean looksLikePayloadVerificationFailure(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (isVirtualMachineException(current)) {
                String message = current.getMessage();
                if (message != null) {
                    String lower = message.toLowerCase(Locale.ROOT);
                    if (lower.contains("verif")
                            || lower.contains("payload changed")
                            || lower.contains("payload has changed")) {
                        return true;
                    }
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    static boolean isVirtualMachineException(Throwable value) {
        return "android.system.virtualmachine.VirtualMachineException"
                .equals(value.getClass().getName());
    }

    static FastLaneException error(String reason, String message, Throwable cause) {
        return new FastLaneException(reason, message, cause);
    }

    static void closeChannel(FastLaneChannel channel, Throwable primary) {
        try {
            channel.close();
        } catch (IOException | RuntimeException failure) {
            primary.addSuppressed(failure);
        }
    }

    static int intField(Class<?> type, String name) throws NoSuchFieldException {
        try {
            return type.getField(name).getInt(null);
        } catch (IllegalAccessException | IllegalArgumentException failure) {
            throw new NoSuchFieldException(name + ": " + failure.getMessage());
        }
    }
}
