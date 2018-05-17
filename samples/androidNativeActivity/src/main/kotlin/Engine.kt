/*
 * Copyright 2010-2018 JetBrains s.r.o.
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
package NativeApplication

import kotlinx.cinterop.*
import platform.android.*
import platform.posix.*
import platform.gles3.*
import platform.linux.*

fun logError(message: String) {
    __android_log_write(ANDROID_LOG_ERROR, "KonanActivity", message)
}

fun logInfo(message: String) {
    __android_log_write(ANDROID_LOG_INFO, "KonanActivity", message)
}

private fun getUnixError() = strerror(posix_errno())!!.toKString()

const val LOOPER_ID_INPUT = 2

inline fun <reified T : CPointed> CPointer<*>?.dereferenceAs(): T = this!!.reinterpret<T>().pointed

class Engine(val state: NativeActivityState): DisposableContainer() {
    private val renderer = Renderer(this, state.activity!!.pointed, state.savedState)
    private var queue: CPointer<AInputQueue>? = null
    private var rendererState: COpaquePointer? = null

    private var currentPoint = Vector2.Zero
    private var startPoint = Vector2.Zero
    private var startTime = 0.0f
    private var animationEndTime = 0.0f
    private var velocity = Vector2.Zero
    private var acceleration = Vector2.Zero

    private var needRedraw = true
    private var animating = false
    private val pointerSize = CPointerVar.size

    private val now = arena.alloc<timespec>()
    private val eventPointer = arena.alloc<COpaquePointerVar>()
    private val inputEvent = arena.alloc<CPointerVar<AInputEvent>>()

    fun mainLoop() {
        val fd = arena.alloc<IntVar>()
        while (true) {
            // Process events.
            eventLoop@ while (true) {
                val id = ALooper_pollAll(if (needRedraw || animating) 0 else -1, fd.ptr, null, null)
                if (id < 0) break@eventLoop
                when (id) {
                    LOOPER_ID_SYS -> if (!processSysEvent(fd)) return // An error occured.
                    LOOPER_ID_INPUT -> processUserInput()
                    else -> logError("Unprocessed event: $id")
                }
            }
            when {
                animating -> {
                    val elapsed = getTime() - startTime
                    if (elapsed >= animationEndTime) {
                        animating = false
                    } else {
                        move(startPoint + velocity * elapsed + acceleration * (elapsed * elapsed * 0.5f))
                        renderer.draw()
                    }
                }

                needRedraw -> renderer.draw()
            }
        }
    }

    override fun dispose() {
        renderer.destroy()
        super.dispose()
    }


    private fun callToManagedAPI() {
        val env = state.activity!!.pointed.env
        println("jni = $env")
    }

    private fun processSysEvent(fd: IntVar): Boolean {
        val readBytes = read(fd.value, eventPointer.ptr, pointerSize.narrow()).toLong()
        if (readBytes != pointerSize.toLong()) {
            logError("Failure reading event, $readBytes read: ${getUnixError()}")
            return true
        }
        try {
            val event = eventPointer.value.dereferenceAs<NativeActivityEvent>()
            println("got ${event.eventKind}")
            when (event.eventKind) {
                NativeActivityEventKind.START -> {
                    logInfo("Activity started")
                    callToManagedAPI()
                }

                NativeActivityEventKind.DESTROY -> {
                    rendererState?.let {
                        free(it)
                        rendererState = null
                    }
                    return false
                }

                NativeActivityEventKind.NATIVE_WINDOW_CREATED -> {
                    val windowEvent = eventPointer.value.dereferenceAs<NativeActivityWindowEvent>()
                    if (!renderer.initialize(windowEvent.window!!))
                        return false
                    logInfo("Renderer initialized")
                    renderer.draw()
                }

                NativeActivityEventKind.INPUT_QUEUE_CREATED -> {
                    val queueEvent = eventPointer.value.dereferenceAs<NativeActivityQueueEvent>()
                    if (queue != null)
                        AInputQueue_detachLooper(queue)
                    queue = queueEvent.queue
                    AInputQueue_attachLooper(queue, state.looper, LOOPER_ID_INPUT, null, null)
                }

                NativeActivityEventKind.INPUT_QUEUE_DESTROYED -> {
                    val queueEvent = eventPointer.value.dereferenceAs<NativeActivityQueueEvent>()
                    AInputQueue_detachLooper(queueEvent.queue)
                }

                NativeActivityEventKind.NATIVE_WINDOW_DESTROYED -> {
                    renderer.destroy()
                }

                NativeActivityEventKind.SAVE_INSTANCE_STATE -> {
                    val saveStateEvent = eventPointer.value.dereferenceAs<NativeActivitySaveStateEvent>()
                    val state = renderer.getState()
                    val dataSize = state.second.signExtend<size_t>()
                    rendererState = realloc(rendererState, dataSize)
                    memcpy(rendererState, state.first, dataSize)
                    logInfo("Saving instance state to $rendererState: $dataSize bytes")
                    saveStateEvent.savedState = rendererState
                    saveStateEvent.savedStateSize = dataSize
                }
            }
        } finally {
            notifySysEventProcessed()
        }
        return true
    }

    private fun getTime(): Float {
        clock_gettime(CLOCK_MONOTONIC, now.ptr)
        return now.tv_sec + now.tv_nsec / 1_000_000_000.0f
    }

    private fun getEventPoint(event: CPointer<AInputEvent>?, i: Int) =
            Vector2(AMotionEvent_getRawX(event, i.signExtend<size_t>()), AMotionEvent_getRawY(event, i.signExtend<size_t>()))

    private fun getEventTime(event: CPointer<AInputEvent>?) =
            AMotionEvent_getEventTime(event) / 1_000_000_000.0f

    private fun processUserInput(): Unit {
        if (AInputQueue_getEvent(queue, inputEvent.ptr) < 0) {
            logError("Failure reading input event")
            return
        }
        val event = inputEvent.value
        val eventType = AInputEvent_getType(event)
        if (eventType == AINPUT_EVENT_TYPE_MOTION) {
            val action = AKeyEvent_getAction(event) and AMOTION_EVENT_ACTION_MASK
            when (action) {
                AMOTION_EVENT_ACTION_DOWN -> {
                    animating = false
                    currentPoint = getEventPoint(event, 0)
                    startTime = getEventTime(event)
                    startPoint = currentPoint
                }

                AMOTION_EVENT_ACTION_UP -> {
                    val endPoint = getEventPoint(event, 0)
                    val endTime = getEventTime(event)
                    animating = true
                    velocity = (endPoint - startPoint) / (endTime - startTime + 1e-9f)
                    if (velocity.length > renderer.screen.length)
                        velocity = velocity * (renderer.screen.length / velocity.length)
                    acceleration = velocity.normalized() * (-renderer.screen.length * 0.5f)
                    animationEndTime = velocity.length / acceleration.length
                    startPoint = endPoint
                    startTime = endTime
                    move(endPoint)
                }

                AMOTION_EVENT_ACTION_MOVE -> {
                    val numberOfPointers = AMotionEvent_getPointerCount(event).toInt()
                    for (i in 0 until numberOfPointers)
                        move(getEventPoint(event, i))
                }
            }
        }
        AInputQueue_finishEvent(queue, event, 1)
    }

    private fun move(newPoint: Vector2) {
        renderer.rotateBy(newPoint - currentPoint)
        currentPoint = newPoint
    }
}
