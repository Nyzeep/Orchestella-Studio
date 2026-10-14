package io.github.nyzeep.orchestella

internal object LocalEditorReentry {
    fun select(
        currentId: String?,
        selectedId: String?,
        closed: Boolean,
        retire: () -> Unit,
        recreate: () -> Unit,
        finish: () -> Unit
    ) {
        if (selectedId.isNullOrBlank()) {
            retire()
            finish()
        } else if (closed || selectedId != currentId) {
            retire()
            recreate()
        }
    }
}
