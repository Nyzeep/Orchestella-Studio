package io.github.nyzeep.orchestella.runtime.contract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Centralized strategy-neutral vocabulary and evaluation rules for execution
 * policy, root-volume format, preflight verdicts, and fallback reasons (W3-00 / R-00).
 *
 * <p>Strictly separates:
 * <ul>
 *   <li><b>Persisted identity</b> ({@link InstanceExecutionIdentity}): what an instance
 *       explicitly records on disk/archive ({@link StrategyDeclaration} and
 *       {@link VolumeFormatDeclaration}), preserving absent legacy defaults and
 *       unrecognized future values without silent mutation.</li>
 *   <li><b>Per-launch diagnostics</b> ({@link LaunchDiagnostic}, {@link PreflightVerdict},
 *       {@link FallbackReason}): ephemeral evaluation state produced for one launch attempt
 *       and never persisted into instance metadata.</li>
 * </ul>
 */
public final class ExecutionPolicyContract {
    private static final byte[] QCOW2_MAGIC = new byte[] { 0x51, 0x46, 0x49, (byte) 0xFB };
    private static final byte[] VMDK_MAGIC = new byte[] { 0x4B, 0x44, 0x4D, 0x56 };

    private ExecutionPolicyContract() {}

    /** Three-state provenance for persisted strategy and volume-format declarations. */
    public enum DeclarationKind {
        /** Key omitted (or null in export JSON) on legacy metadata prior to explicit declarations. */
        ABSENT_LEGACY,
        /** Key present and matches an enum value understood by this build. */
        EXPLICIT_KNOWN,
        /** Key present with a non-blank value not understood by this build. */
        EXPLICIT_UNKNOWN
    }

    /** Shared immutable representation of a three-state enum declaration. */
    private static final class ParsedDeclaration<E extends Enum<E>> {
        private final DeclarationKind kind;
        private final E value;
        private final String rawValue;

        private ParsedDeclaration(DeclarationKind kind, E value, String rawValue) {
            this.kind = kind;
            this.value = value;
            this.rawValue = rawValue;
        }

        private static <E extends Enum<E>> ParsedDeclaration<E> absent() {
            return new ParsedDeclaration<>(DeclarationKind.ABSENT_LEGACY, null, null);
        }

        private static <E extends Enum<E>> ParsedDeclaration<E> known(E value) {
            Objects.requireNonNull(value, "value");
            return new ParsedDeclaration<>(DeclarationKind.EXPLICIT_KNOWN, value, value.name());
        }

        private static <E extends Enum<E>> ParsedDeclaration<E> parse(
                String raw, String label, Function<String, E> lookup) {
            if (raw == null) {
                return absent();
            }
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("blank " + label + " declaration");
            }
            try {
                return known(lookup.apply(trimmed));
            } catch (IllegalArgumentException unrecognized) {
                return new ParsedDeclaration<>(DeclarationKind.EXPLICIT_UNKNOWN, null, trimmed);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ParsedDeclaration<?> that)) return false;
            return kind == that.kind
                    && Objects.equals(value, that.value)
                    && Objects.equals(rawValue, that.rawValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, value, rawValue);
        }

        @Override
        public String toString() {
            return switch (kind) {
                case ABSENT_LEGACY -> "ABSENT_LEGACY";
                case EXPLICIT_KNOWN -> "EXPLICIT_KNOWN(" + value + ")";
                case EXPLICIT_UNKNOWN -> "EXPLICIT_UNKNOWN(" + rawValue + ")";
            };
        }
    }

    /** Persisted execution-strategy declaration; null stays absent legacy, blank fails closed. */
    public static final class StrategyDeclaration {
        private static final StrategyDeclaration ABSENT =
                new StrategyDeclaration(ParsedDeclaration.absent());

        private final ParsedDeclaration<FullLinuxContract.ExecutionStrategy> parsed;

        private StrategyDeclaration(ParsedDeclaration<FullLinuxContract.ExecutionStrategy> parsed) {
            this.parsed = parsed;
        }

        public static StrategyDeclaration absentLegacy() {
            return ABSENT;
        }

        public static StrategyDeclaration of(FullLinuxContract.ExecutionStrategy strategy) {
            return new StrategyDeclaration(ParsedDeclaration.known(strategy));
        }

        public static StrategyDeclaration parse(String raw) {
            ParsedDeclaration<FullLinuxContract.ExecutionStrategy> parsed = ParsedDeclaration.parse(
                    raw, "execution strategy", FullLinuxContract.ExecutionStrategy::valueOf);
            return parsed.kind == DeclarationKind.ABSENT_LEGACY ? ABSENT : new StrategyDeclaration(parsed);
        }

        public DeclarationKind kind() {
            return parsed.kind;
        }

        public FullLinuxContract.ExecutionStrategy strategy() {
            return parsed.value;
        }

        public String rawValue() {
            return parsed.rawValue;
        }

        public boolean isAbsent() {
            return parsed.kind == DeclarationKind.ABSENT_LEGACY;
        }

        public boolean isKnown() {
            return parsed.kind == DeclarationKind.EXPLICIT_KNOWN;
        }

        public boolean isUnknown() {
            return parsed.kind == DeclarationKind.EXPLICIT_UNKNOWN;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof StrategyDeclaration that && parsed.equals(that.parsed));
        }

        @Override
        public int hashCode() {
            return parsed.hashCode();
        }

        @Override
        public String toString() {
            return parsed.toString();
        }
    }

    /** Persisted root-volume format declaration (ADR-0007). */
    public static final class VolumeFormatDeclaration {
        private static final VolumeFormatDeclaration ABSENT =
                new VolumeFormatDeclaration(ParsedDeclaration.absent());

        private final ParsedDeclaration<StorageContract.RootVolumeFormat> parsed;

        private VolumeFormatDeclaration(ParsedDeclaration<StorageContract.RootVolumeFormat> parsed) {
            this.parsed = parsed;
        }

        public static VolumeFormatDeclaration absentLegacy() {
            return ABSENT;
        }

        public static VolumeFormatDeclaration of(StorageContract.RootVolumeFormat format) {
            return new VolumeFormatDeclaration(ParsedDeclaration.known(format));
        }

        public static VolumeFormatDeclaration parse(String raw) {
            ParsedDeclaration<StorageContract.RootVolumeFormat> parsed = ParsedDeclaration.parse(
                    raw, "root volume format", StorageContract.RootVolumeFormat::valueOf);
            return parsed.kind == DeclarationKind.ABSENT_LEGACY ? ABSENT : new VolumeFormatDeclaration(parsed);
        }

        public DeclarationKind kind() {
            return parsed.kind;
        }

        public StorageContract.RootVolumeFormat format() {
            return parsed.value;
        }

        public String rawValue() {
            return parsed.rawValue;
        }

        public boolean isAbsent() {
            return parsed.kind == DeclarationKind.ABSENT_LEGACY;
        }

        public boolean isKnown() {
            return parsed.kind == DeclarationKind.EXPLICIT_KNOWN;
        }

        public boolean isUnknown() {
            return parsed.kind == DeclarationKind.EXPLICIT_UNKNOWN;
        }

        @Override
        public boolean equals(Object o) {
            return this == o || (o instanceof VolumeFormatDeclaration that && parsed.equals(that.parsed));
        }

        @Override
        public int hashCode() {
            return parsed.hashCode();
        }

        @Override
        public String toString() {
            return parsed.toString();
        }
    }

    /** Immutable persisted execution identity pairing strategy and root-volume format declarations. */
    public static final class InstanceExecutionIdentity {
        private static final InstanceExecutionIdentity LEGACY_ABSENT =
                new InstanceExecutionIdentity(
                        StrategyDeclaration.absentLegacy(),
                        VolumeFormatDeclaration.absentLegacy());

        private final StrategyDeclaration strategy;
        private final VolumeFormatDeclaration volumeFormat;

        private InstanceExecutionIdentity(
                StrategyDeclaration strategy, VolumeFormatDeclaration volumeFormat) {
            this.strategy = Objects.requireNonNull(strategy, "strategy");
            this.volumeFormat = Objects.requireNonNull(volumeFormat, "volumeFormat");
        }

        public static InstanceExecutionIdentity legacyAbsent() {
            return LEGACY_ABSENT;
        }

        public static InstanceExecutionIdentity explicit(
                FullLinuxContract.ExecutionStrategy strategy,
                StorageContract.RootVolumeFormat volumeFormat) {
            return new InstanceExecutionIdentity(
                    StrategyDeclaration.of(strategy),
                    VolumeFormatDeclaration.of(volumeFormat));
        }

        public static InstanceExecutionIdentity of(
                StrategyDeclaration strategy, VolumeFormatDeclaration volumeFormat) {
            StrategyDeclaration effectiveStrategy =
                    strategy != null ? strategy : StrategyDeclaration.absentLegacy();
            VolumeFormatDeclaration effectiveFormat =
                    volumeFormat != null ? volumeFormat : VolumeFormatDeclaration.absentLegacy();
            if (effectiveStrategy.isAbsent() && effectiveFormat.isAbsent()) {
                return LEGACY_ABSENT;
            }
            return new InstanceExecutionIdentity(effectiveStrategy, effectiveFormat);
        }

        public StrategyDeclaration strategy() {
            return strategy;
        }

        public VolumeFormatDeclaration volumeFormat() {
            return volumeFormat;
        }

        public boolean isLegacyAbsent() {
            return strategy.isAbsent() && volumeFormat.isAbsent();
        }

        public boolean hasUnknownStrategy() {
            return strategy.isUnknown();
        }

        public boolean hasUnrecognizedVolumeFormat() {
            return volumeFormat.isUnknown();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof InstanceExecutionIdentity that)) return false;
            return Objects.equals(strategy, that.strategy)
                    && Objects.equals(volumeFormat, that.volumeFormat);
        }

        @Override
        public int hashCode() {
            return Objects.hash(strategy, volumeFormat);
        }

        @Override
        public String toString() {
            return "InstanceExecutionIdentity{strategy=" + strategy
                    + ", volumeFormat=" + volumeFormat + '}';
        }
    }

    /** Ephemeral outcome of pre-launch policy and volume compatibility checks. */
    public enum PreflightStatus {
        ALLOWED,
        FALLBACK_ALLOWED,
        REFUSED
    }

    /** Machine-readable preflight reason codes. */
    public enum PreflightCode {
        READY,
        LEGACY_DEFAULT_ROUTE,
        UNKNOWN_EXECUTION_STRATEGY,
        UNRECOGNIZED_VOLUME_FORMAT,
        VOLUME_FORMAT_CONFLICT,
        STRATEGY_NOT_AVAILABLE
    }

    /** Ephemeral preflight verdict for a single launch attempt. */
    public static final class PreflightVerdict {
        private final PreflightStatus status;
        private final PreflightCode code;
        private final String detail;

        public PreflightVerdict(PreflightStatus status, PreflightCode code, String detail) {
            this.status = Objects.requireNonNull(status, "status");
            this.code = Objects.requireNonNull(code, "code");
            this.detail = detail == null ? "" : detail;
        }

        public PreflightStatus status() {
            return status;
        }

        public PreflightCode code() {
            return code;
        }

        public String detail() {
            return detail;
        }

        public boolean isAllowed() {
            return status == PreflightStatus.ALLOWED || status == PreflightStatus.FALLBACK_ALLOWED;
        }

        public boolean isRefused() {
            return status == PreflightStatus.REFUSED;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PreflightVerdict that)) return false;
            return status == that.status && code == that.code && Objects.equals(detail, that.detail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, code, detail);
        }

        @Override
        public String toString() {
            return "PreflightVerdict{status=" + status + ", code=" + code + ", detail='" + detail + "'}";
        }
    }

    /** Strategy-neutral per-launch fallback reason when an allowed launch degrades. */
    public enum FallbackReason {
        NONE,
        HARDWARE_PERMISSION_DENIED,
        HARDWARE_UNSUPPORTED,
        HARDWARE_CONFIG_MISMATCH,
        HARDWARE_UNAVAILABLE
    }

    /** Ephemeral per-launch diagnostic snapshot; never persisted into instance metadata. */
    public static final class LaunchDiagnostic {
        private final InstanceExecutionIdentity persistedIdentity;
        private final FullLinuxContract.ExecutionStrategy resolvedStrategy;
        private final PreflightVerdict preflightVerdict;
        private final FallbackReason fallbackReason;
        private final String fallbackDetail;
        private final long evaluatedAtMillis;

        public LaunchDiagnostic(
                InstanceExecutionIdentity persistedIdentity,
                FullLinuxContract.ExecutionStrategy resolvedStrategy,
                PreflightVerdict preflightVerdict,
                FallbackReason fallbackReason,
                String fallbackDetail,
                long evaluatedAtMillis) {
            this.persistedIdentity = Objects.requireNonNull(persistedIdentity, "persistedIdentity");
            this.resolvedStrategy = resolvedStrategy;
            this.preflightVerdict = Objects.requireNonNull(preflightVerdict, "preflightVerdict");
            this.fallbackReason = Objects.requireNonNull(fallbackReason, "fallbackReason");
            this.fallbackDetail = fallbackDetail;
            this.evaluatedAtMillis = evaluatedAtMillis;
        }

        public InstanceExecutionIdentity persistedIdentity() {
            return persistedIdentity;
        }

        public FullLinuxContract.ExecutionStrategy resolvedStrategy() {
            return resolvedStrategy;
        }

        public PreflightVerdict preflightVerdict() {
            return preflightVerdict;
        }

        public FallbackReason fallbackReason() {
            return fallbackReason;
        }

        public String fallbackDetail() {
            return fallbackDetail;
        }

        public long evaluatedAtMillis() {
            return evaluatedAtMillis;
        }

        public LaunchDiagnostic withFallback(
                FullLinuxContract.ExecutionStrategy nextResolvedStrategy,
                FallbackReason reason,
                String detail) {
            return new LaunchDiagnostic(
                    persistedIdentity,
                    nextResolvedStrategy,
                    new PreflightVerdict(
                            PreflightStatus.FALLBACK_ALLOWED,
                            preflightVerdict.code(),
                            preflightVerdict.detail()),
                    Objects.requireNonNull(reason, "reason"),
                    detail,
                    evaluatedAtMillis);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LaunchDiagnostic that)) return false;
            return evaluatedAtMillis == that.evaluatedAtMillis
                    && Objects.equals(persistedIdentity, that.persistedIdentity)
                    && resolvedStrategy == that.resolvedStrategy
                    && Objects.equals(preflightVerdict, that.preflightVerdict)
                    && fallbackReason == that.fallbackReason
                    && Objects.equals(fallbackDetail, that.fallbackDetail);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    persistedIdentity,
                    resolvedStrategy,
                    preflightVerdict,
                    fallbackReason,
                    fallbackDetail,
                    evaluatedAtMillis);
        }
    }

    /** Refusal thrown before launch transitions when persisted identity or volume format is incompatible. */
    public static final class ExecutionPolicyRefusalException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final LaunchDiagnostic diagnostic;

        public ExecutionPolicyRefusalException(LaunchDiagnostic diagnostic) {
            super(formatMessage(Objects.requireNonNull(diagnostic, "diagnostic")));
            this.diagnostic = diagnostic;
        }

        public LaunchDiagnostic diagnostic() {
            return diagnostic;
        }

        public PreflightVerdict verdict() {
            return diagnostic.preflightVerdict();
        }

        private static String formatMessage(LaunchDiagnostic diagnostic) {
            PreflightVerdict verdict = diagnostic.preflightVerdict();
            if (verdict.detail().isEmpty()) {
                return verdict.code().name();
            }
            return verdict.code().name() + ": " + verdict.detail();
        }
    }

    /**
     * Read-only volume-format check. Returns {@code null} when no conflict is detected, or a
     * {@link PreflightVerdict} with {@link PreflightStatus#REFUSED} when the format declaration is
     * unrecognized or an existing file header conflicts with {@link StorageContract.RootVolumeFormat#RAW_SPARSE}.
     */
    public static PreflightVerdict detectVolumeFormatConflict(
            Path rootVolume, VolumeFormatDeclaration declared) {
        VolumeFormatDeclaration effective =
                declared != null ? declared : VolumeFormatDeclaration.absentLegacy();
        if (effective.isUnknown()) {
            return new PreflightVerdict(
                    PreflightStatus.REFUSED,
                    PreflightCode.UNRECOGNIZED_VOLUME_FORMAT,
                    "unrecognized root volume format: " + effective.rawValue());
        }
        if (rootVolume == null || !Files.exists(rootVolume)) {
            return null;
        }
        if (Files.isDirectory(rootVolume)) {
            return new PreflightVerdict(
                    PreflightStatus.REFUSED,
                    PreflightCode.VOLUME_FORMAT_CONFLICT,
                    "root volume path is a directory, expected raw sparse file");
        }
        if (Files.isRegularFile(rootVolume)) {
            byte[] header = new byte[4];
            try (InputStream in = Files.newInputStream(rootVolume)) {
                int read = in.readNBytes(header, 0, header.length);
                if (read == 4) {
                    if (matchesMagic(header, QCOW2_MAGIC)) {
                        return new PreflightVerdict(
                                PreflightStatus.REFUSED,
                                PreflightCode.VOLUME_FORMAT_CONFLICT,
                                "root volume has QCOW2 container header, expected raw sparse");
                    }
                    if (matchesMagic(header, VMDK_MAGIC)) {
                        return new PreflightVerdict(
                                PreflightStatus.REFUSED,
                                PreflightCode.VOLUME_FORMAT_CONFLICT,
                                "root volume has VMDK container header, expected raw sparse");
                    }
                }
            } catch (IOException unreadable) {
                return new PreflightVerdict(
                        PreflightStatus.REFUSED,
                        PreflightCode.VOLUME_FORMAT_CONFLICT,
                        "unable to inspect root volume header: " + unreadable.getMessage());
            }
        }
        return null;
    }

    /** Evaluates pre-launch policy compatibility without mutating metadata or disk files. */
    public static LaunchDiagnostic evaluateLaunch(
            InstanceExecutionIdentity identity,
            Path rootVolume,
            Set<FullLinuxContract.ExecutionStrategy> availableExplicitStrategies,
            long nowMillis) {
        InstanceExecutionIdentity effectiveIdentity =
                identity != null ? identity : InstanceExecutionIdentity.legacyAbsent();
        Set<FullLinuxContract.ExecutionStrategy> available =
                availableExplicitStrategies != null ? availableExplicitStrategies : Set.of();
        if (effectiveIdentity.hasUnknownStrategy()) {
            return refused(
                    effectiveIdentity,
                    PreflightCode.UNKNOWN_EXECUTION_STRATEGY,
                    "unknown execution strategy: " + effectiveIdentity.strategy().rawValue(),
                    nowMillis);
        }
        PreflightVerdict formatConflict =
                detectVolumeFormatConflict(rootVolume, effectiveIdentity.volumeFormat());
        if (formatConflict != null) {
            return new LaunchDiagnostic(
                    effectiveIdentity,
                    null,
                    formatConflict,
                    FallbackReason.NONE,
                    null,
                    nowMillis);
        }
        StrategyDeclaration strategyDeclaration = effectiveIdentity.strategy();
        if (strategyDeclaration.isAbsent()) {
            return new LaunchDiagnostic(
                    effectiveIdentity,
                    null,
                    new PreflightVerdict(
                            PreflightStatus.ALLOWED,
                            PreflightCode.LEGACY_DEFAULT_ROUTE,
                            "legacy absent execution strategy uses existing launch routing"),
                    FallbackReason.NONE,
                    null,
                    nowMillis);
        }
        FullLinuxContract.ExecutionStrategy explicit = strategyDeclaration.strategy();
        if (!available.contains(explicit)) {
            return refused(
                    effectiveIdentity,
                    PreflightCode.STRATEGY_NOT_AVAILABLE,
                    "explicit execution strategy unavailable in this channel: " + explicit.name(),
                    nowMillis);
        }
        return new LaunchDiagnostic(
                effectiveIdentity,
                explicit,
                new PreflightVerdict(
                        PreflightStatus.ALLOWED,
                        PreflightCode.READY,
                        "explicit execution strategy ready: " + explicit.name()),
                FallbackReason.NONE,
                null,
                nowMillis);
    }

    private static LaunchDiagnostic refused(
            InstanceExecutionIdentity identity,
            PreflightCode code,
            String detail,
            long nowMillis) {
        return new LaunchDiagnostic(
                identity,
                null,
                new PreflightVerdict(PreflightStatus.REFUSED, code, detail),
                FallbackReason.NONE,
                null,
                nowMillis);
    }

    private static boolean matchesMagic(byte[] actual, byte[] expected) {
        for (int index = 0; index < expected.length; index++) {
            if (actual[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
