package org.jetbrains.kotlin.konan.library


interface KonanLibraryVersioning {
    val libraryVersion: String?
    val compilerVersion: String
    val abiVersion: Int
}
