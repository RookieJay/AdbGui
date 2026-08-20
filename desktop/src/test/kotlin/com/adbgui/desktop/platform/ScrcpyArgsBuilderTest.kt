package com.adbgui.desktop.platform

import com.adbgui.core.domain.ScrcpyOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScrcpyArgsBuilderTest {

    private val path = "C:/tools/scrcpy.exe"
    private val serial = "192.168.1.42:5555"

    @Test
    fun starts_with_scrcpy_path_and_serial() {
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions())
        assertEquals(path, args.first())
        assertEquals("-s", args[1])
        assertEquals(serial, args[2])
    }

    @Test
    fun all_flags_on_emits_every_flag() {
        val opts = ScrcpyOptions(
            maxSize = 1920,
            stayAwake = true,
            turnScreenOff = true,
            recordPath = "/rec/cap.mp4",
            alwaysOnTop = true,
            fullscreen = true,
            maxFps = 60,
            noAudio = true,
        )
        val args = ScrcpyArgsBuilder.build(path, serial, opts)
        assertTrue("--max-size 1920".split(" ").all { it in args })
        assertTrue("--stay-awake" in args)
        assertTrue("--turn-screen-off" in args)
        assertTrue("--record /rec/cap.mp4".split(" ").all { it in args })
        assertTrue("--always-on-top" in args)
        assertTrue("--fullscreen" in args)
        assertTrue("--max-fps 60".split(" ").all { it in args })
        assertTrue("--no-audio" in args)
    }

    @Test
    fun stay_awake_true_by_default_emits_flag() {
        // stayAwake defaults to true → --stay-awake present even with empty options.
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions())
        assertTrue("--stay-awake" in args)
    }

    @Test
    fun stay_awake_false_omits_flag() {
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions(stayAwake = false))
        assertFalse("--stay-awake" in args)
    }

    @Test
    fun zero_max_size_omits_flag() {
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions(maxSize = 0))
        assertFalse("--max-size" in args)
    }

    @Test
    fun zero_max_fps_omits_flag() {
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions(maxFps = 0))
        assertFalse("--max-fps" in args)
    }

    @Test
    fun null_record_path_omits_flag() {
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions(recordPath = null))
        assertFalse("--record" in args)
    }

    @Test
    fun blank_record_path_omits_flag() {
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions(recordPath = "   "))
        assertFalse("--record" in args)
    }

    @Test
    fun all_new_toggles_off_by_default_omit_flags() {
        val args = ScrcpyArgsBuilder.build(path, serial, ScrcpyOptions())
        assertFalse("--always-on-top" in args)
        assertFalse("--fullscreen" in args)
        assertFalse("--no-audio" in args)
    }
}
