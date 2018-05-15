package org.jetbrains.kotlin.konan.library

import org.jetbrains.kotlin.backend.konan.library.KonanLibrary
import org.jetbrains.kotlin.konan.library.KonanLibraryVersioning
import org.jetbrains.kotlin.konan.target.KonanTarget


data class UnresolvedLibrary(
        val path: String,
        val target: KonanTarget?,
        override val abiVersion: Int,
        override val compilerVersion: String,
        override val libraryVersion: String?) : KonanLibraryVersioning {

    fun substitutePath(newPath: String): UnresolvedLibrary {
        return UnresolvedLibrary(newPath, target, abiVersion, compilerVersion, libraryVersion)
    }
}