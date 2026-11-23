package io.github.nyzeep.orchestella.runtime.engineuml;

import java.io.IOException;

/** Spawns a standalone UML guest process for a prepared {@link UmlLaunchSpec}. */
@FunctionalInterface
public interface UmlProcessFactory {
    UmlProcessHandle spawn(UmlLaunchSpec spec, int initialColumns, int initialRows)
            throws IOException;
}
