package android.system.virtualmachine;

/** Test-only equivalent of the official exception; it has no error code. */
public class VirtualMachineException extends Exception {
    private static final long serialVersionUID = 1L;

    public VirtualMachineException(String message) {
        super(message);
    }

    public VirtualMachineException(String message, Throwable cause) {
        super(message, cause);
    }
}
