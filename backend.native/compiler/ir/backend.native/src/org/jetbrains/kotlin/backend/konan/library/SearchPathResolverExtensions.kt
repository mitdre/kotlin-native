/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan.library

import org.jetbrains.kotlin.backend.konan.library.impl.LibraryReaderImpl
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.KonanLibraryReader
import org.jetbrains.kotlin.konan.library.SearchPathResolver
import org.jetbrains.kotlin.konan.library.UnresolvedLibrary
import org.jetbrains.kotlin.konan.target.KonanTarget

fun SearchPathResolver.resolveImmediateLibraries(libraryNames: List<String>,
                                                 target: KonanTarget,
                                                 abiVersion: Int = 1,
                                                 noStdLib: Boolean = false,
                                                 noDefaultLibs: Boolean = false): List<LibraryReaderImpl> {
    val userProvidedLibraries = libraryNames
            .map { resolve(it) }
            .map{ LibraryReaderImpl(it, abiVersion, target) }

    val defaultLibraries = defaultLinks(nostdlib = noStdLib, noDefaultLibs = noDefaultLibs).map {
        LibraryReaderImpl(it, abiVersion, target, isDefaultLibrary = true)
    }

    // Make sure the user provided ones appear first, so that 
    // they have precedence over defaults when duplicates are eliminated.
    val resolvedLibraries = userProvidedLibraries + defaultLibraries

    warnOnLibraryDuplicates(resolvedLibraries.map { it.libraryFile })

    return resolvedLibraries.distinctBy { it.libraryFile.absolutePath }
}

private fun SearchPathResolver.warnOnLibraryDuplicates(resolvedLibraries: List<File>) {

    val duplicates = resolvedLibraries.groupBy { it.absolutePath } .values.filter { it.size > 1 }

    duplicates.forEach {
        logger("library included more than once: ${it.first().absolutePath}")
    }
}

fun SearchPathResolver.libraryMatch(candidate: LibraryReaderImpl, unresolved: UnresolvedLibrary): Boolean {
    val unresolvedTarget = unresolved.target
    val candidatePath = candidate.libraryFile.absolutePath

    if (unresolvedTarget != null && !candidate.targetList.contains(unresolvedTarget!!.visibleName)) {
        logger("skipping $candidatePath as it doesn't support the needed hardware target. Expected `$unresolvedTarget`, found ${candidate.targetList}")
        return false
    }

    if (candidate.compilerVersion != unresolved.compilerVersion) {
        logger("skipping $candidatePath. The compiler versions don't match. Expected '${unresolved.compilerVersion}', found '${candidate.compilerVersion}'")
        return false
    }

    if (candidate.abiVersion != unresolved.abiVersion) {
        logger("skipping $candidatePath. The abi versions don't match. Expected `${unresolved.abiVersion}`, found ${candidate.abiVersion}")
        return false
    }

    if (candidate.libraryVersion != unresolved.libraryVersion &&
        candidate.libraryVersion != null &&
        unresolved.libraryVersion != null) {

        logger("skipping $candidatePath. The library versions don't match. Expected `${unresolved.libraryVersion}`, found ${candidate.libraryVersion}")
        return false
    }

    return true
}

fun SearchPathResolver.resolve(unresolved: UnresolvedLibrary): LibraryReaderImpl {
    val givenPath = unresolved.path
    val files = resolutionList(givenPath)
    val matching = files.map { LibraryReaderImpl(it, unresolved.target) }
            .map { it.takeIf {libraryMatch(it, unresolved)} }
            .filterNotNull()

    if (matching.isEmpty()) error("Could not find \"$givenPath\" in ${searchRoots.map{it.absolutePath}}.")
    return matching.first()
}

fun SearchPathResolver.resolveLibrariesRecursive(immediateLibraries: List<LibraryReaderImpl>,
                                                 target: KonanTarget,
                                                 abiVersion: Int) {
    val cache = mutableMapOf<File, LibraryReaderImpl>()
    cache.putAll(immediateLibraries.map { it.libraryFile.absoluteFile to it })
    var newDependencies = cache.values.toList()
    do {
        newDependencies = newDependencies.map { library: LibraryReaderImpl ->
            library.unresolvedDependencies
                    .map { it to resolve(it) }
                    .map { (unresolved, resolved) ->
                        val absoluteFile = resolved.libraryFile.absoluteFile
                        if (absoluteFile in cache) {
                            library.resolvedDependencies.add(cache[absoluteFile]!!)
                            null
                        } else {
                            cache.put(absoluteFile ,resolved)
                            library.resolvedDependencies.add(resolved)
                            resolved
                        }
            }.filterNotNull()
        } .flatten()
    } while (newDependencies.isNotEmpty())
}

fun List<LibraryReaderImpl>.withResolvedDependencies(): List<LibraryReaderImpl> {
    val result = mutableSetOf<LibraryReaderImpl>()
    result.addAll(this)
    var newDependencies = result.toList()
    do {
        newDependencies = newDependencies
            .map { it -> it.resolvedDependencies } .flatten()
            .filter { it !in result }
        result.addAll(newDependencies)
    } while (newDependencies.isNotEmpty())
    return result.toList()
}

fun SearchPathResolver.resolveLibrariesRecursive(libraryNames: List<String>,
                                                 target: KonanTarget,
                                                 abiVersion: Int = 1,
                                                 noStdLib: Boolean = false,
                                                 noDefaultLibs: Boolean = false): List<LibraryReaderImpl> {
    val immediateLibraries = resolveImmediateLibraries(
                    libraryNames = libraryNames,
                    target = target,
                    abiVersion = abiVersion,
                    noStdLib = noStdLib,
                    noDefaultLibs = noDefaultLibs
            )
    resolveLibrariesRecursive(immediateLibraries, target, abiVersion)
    return immediateLibraries.withResolvedDependencies()
}
