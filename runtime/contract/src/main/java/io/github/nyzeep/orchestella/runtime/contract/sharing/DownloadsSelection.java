package io.github.nyzeep.orchestella.runtime.contract.sharing;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Exact explicit Downloads file scope. Opaque/unknown providers and directory selections fail closed. */
public final class DownloadsSelection {
    private static final String DOCUMENTS = "com.android.externalstorage.documents";
    private final URI root;
    private final Set<URI> files;
    private final Path fileRoot;
    private final String documentRoot;
    private final SourceProof proof;
    private final Map<URI, String> versions = new HashMap<>();

    /** Trusted adapter must prove current grant/provider/current-user Downloads, held object identity and version.
     * It must fail when proof is unavailable. Returned version is an opaque local comparison token, never content.
     * URI syntax alone is not proof; source input must come from the same held object, not a reopened path.
     */
    @FunctionalInterface public interface SourceProof {
        String verify(URI downloadsRoot, URI selectedSource) throws IOException;
    }

    private DownloadsSelection(URI root, Set<URI> files, SourceProof proof) throws IOException {
        if (proof == null) { throw new SecurityException("Trusted source identity proof is required"); }
        this.proof = proof;
        this.root = Objects.requireNonNull(root, "root");
        this.files = Set.copyOf(Objects.requireNonNull(files, "files"));
        if (this.files.isEmpty() || this.files.size() > 10000) { throw new SecurityException("A bounded exact selection is required"); }
        plainUri(root);
        if ("file".equals(root.getScheme())) {
            if (root.getRawAuthority() != null) { throw new SecurityException("Remote file URI is forbidden"); }
            PathBoundary.decode(root.getRawPath(), false);
            fileRoot = PathBoundary.directory(Paths.get(root));
            String name = fileRoot.getFileName().toString();
            if (!"Download".equals(name) && !"Downloads".equals(name)) {
                throw new SecurityException("Downloads root must be known explicitly");
            }
            documentRoot = null;
        } else if ("content".equals(root.getScheme()) && DOCUMENTS.equals(root.getRawAuthority())) {
            String path = PathBoundary.decode(root.getRawPath(), true);
            if (!"/tree/primary:Download".equals(path) && !"/tree/primary:Downloads".equals(path)) {
                throw new SecurityException("Unknown Downloads document root");
            }
            documentRoot = path.substring("/tree/".length());
            fileRoot = null;
        } else { throw new SecurityException("Unsupported or unknown Downloads scope"); }
        for (URI file : this.files) {
            validateMember(file);
            String version = proof.verify(root, file);
            if (version == null || version.isBlank() || version.length() > 256) {
                throw new SecurityException("Stable source identity could not be proved");
            }
            versions.put(file, version);
        }
    }

    /** Caller supplies the user's visible selection, not a server-provided or background scope. */
    public static DownloadsSelection authorize(boolean userAuthorized, URI downloadsRoot, Set<URI> selectedFiles,
                                               SourceProof sourceProof) throws IOException {
        if (!userAuthorized) { throw new SecurityException("Explicit file selection authorization required"); }
        return new DownloadsSelection(downloadsRoot, selectedFiles, sourceProof);
    }

    public URI downloadsRoot() { return root; }
    public Set<URI> selectedFiles() { return files; }
    @Override public String toString() { return "DownloadsSelection[explicit-files=" + files.size() + "]"; }

    void requireSelected(URI source) throws IOException {
        validateMember(source);
        if (!versions.get(source).equals(proof.verify(root, source))) {
            throw new SecurityException("Selected source identity or version changed");
        }
    }

    private void validateMember(URI source) throws IOException {
        if (!files.contains(source)) { throw new SecurityException("Source was not selected"); }
        plainUri(source);
        if (fileRoot != null) {
            if (!"file".equals(source.getScheme()) || source.getRawAuthority() != null) {
                throw new SecurityException("File source URI differs from scope");
            }
            PathBoundary.decode(source.getRawPath(), false);
            Path path = Paths.get(source);
            if (!path.equals(path.normalize()) || !path.startsWith(fileRoot) || path.equals(fileRoot)) {
                throw new SecurityException("Source is outside Downloads scope");
            }
            for (Path part : fileRoot.relativize(path)) { PathBoundary.name(part.toString(), 240); }
            PathBoundary.directory(fileRoot);
            PathBoundary.regularFile(path);
        } else {
            if (!"content".equals(source.getScheme()) || !DOCUMENTS.equals(source.getRawAuthority())) {
                throw new SecurityException("Document source authority differs from scope");
            }
            String raw = source.getRawPath();
            String prefix = root.getRawPath() + "/document/";
            String id;
            if (raw.startsWith(prefix)) { id = PathBoundary.decode(raw.substring(prefix.length()), true); }
            else if (raw.startsWith("/document/")) { id = PathBoundary.decode(raw.substring("/document/".length()), true); }
            else { throw new SecurityException("Unknown document URI layout"); }
            if (!id.startsWith(documentRoot + "/")) { throw new SecurityException("Document is outside Downloads scope"); }
            String relative = id.substring(documentRoot.length() + 1);
            for (String part : relative.split("/", -1)) { PathBoundary.name(part, 240); }
        }
    }

    private static void plainUri(URI uri) {
        if (uri == null || uri.toString().length() > 4096 || !uri.isAbsolute() || uri.isOpaque()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new SecurityException("Invalid scoped source URI");
        }
    }
}
