package app.logdate.client.media.device

import app.logdate.client.text.toUiText
import logdate.client.media.generated.resources.Res
import logdate.client.media.generated.resources.media_device_back_camera
import logdate.client.media.generated.resources.media_device_camera_system_controlled
import logdate.client.media.generated.resources.media_device_microphone_system_controlled
import logdate.client.media.generated.resources.media_device_microphone_unavailable
import logdate.client.media.generated.resources.media_device_output_system_controlled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaDeviceSelectionUiStateTest {
    @Test
    fun `selectedDevice returns explicit selected device`() {
        val selection =
            MediaDeviceSelectionUiState(
                kind = MediaDeviceKind.AUDIO_OUTPUT,
                devices =
                    listOf(
                        DefaultMediaDevices.systemOutput,
                        DefaultMediaDevices.systemOutput.copy(id = "bluetooth", label = "Bluetooth headphones".toUiText()),
                    ),
                selectedDeviceId = "bluetooth",
            )

        assertEquals("Bluetooth headphones".toUiText(), selection.selectedDevice?.label)
    }

    @Test
    fun `selectedDevice falls back to first available device`() {
        val selection =
            MediaDeviceSelectionUiState(
                kind = MediaDeviceKind.AUDIO_INPUT,
                devices =
                    listOf(
                        DefaultMediaDevices.systemMicrophone.copy(isAvailable = false),
                        DefaultMediaDevices.systemMicrophone.copy(id = "usb", label = "USB microphone".toUiText()),
                    ),
                selectedDeviceId = "missing",
            )

        assertEquals("USB microphone".toUiText(), selection.selectedDevice?.label)
    }

    @Test
    fun `selectedDevice is null when there are no available devices`() {
        val selection =
            MediaDeviceSelectionUiState(
                kind = MediaDeviceKind.CAMERA,
                devices = emptyList(),
                selectedDeviceId = null,
            )

        assertNull(selection.selectedDevice)
    }

    @Test
    fun `audio input resolver selects preferred microphone when it is available`() {
        val builtIn = DefaultMediaDevices.systemMicrophone.copy(id = "built-in", label = "Built-in microphone".toUiText())
        val usb =
            DefaultMediaDevices.systemMicrophone.copy(
                id = "usb",
                label = "USB microphone".toUiText(),
                category = MediaDeviceCategory.USB,
                isExternal = true,
            )

        val selection =
            MediaDeviceSelectionResolver.resolveAudioInput(
                devices = listOf(builtIn, usb),
                preferredDeviceId = usb.id,
            )

        assertEquals(usb.id, selection.selectedDeviceId)
        assertEquals("USB microphone".toUiText(), selection.selectedDevice?.label)
        assertEquals(true, selection.isSelectionControllable)
        assertNull(selection.routeControlMessage)
    }

    @Test
    fun `audio input resolver falls back with copy when preferred microphone is unplugged`() {
        val builtIn = DefaultMediaDevices.systemMicrophone.copy(id = "built-in", label = "Built-in microphone".toUiText())

        val selection =
            MediaDeviceSelectionResolver.resolveAudioInput(
                devices = listOf(builtIn),
                preferredDeviceId = "usb",
            )

        assertEquals(builtIn.id, selection.selectedDeviceId)
        assertEquals("Built-in microphone".toUiText(), selection.selectedDevice?.label)
        assertEquals(
            Res.string.media_device_microphone_unavailable.toUiText(builtIn.label),
            selection.routeControlMessage,
        )
    }

    @Test
    fun `audio input resolver restores preferred microphone when it is plugged back in`() {
        val builtIn = DefaultMediaDevices.systemMicrophone.copy(id = "built-in", label = "Built-in microphone".toUiText())
        val usb =
            DefaultMediaDevices.systemMicrophone.copy(
                id = "usb",
                label = "USB microphone".toUiText(),
                category = MediaDeviceCategory.USB,
                isExternal = true,
            )

        val selectionWithoutUsb =
            MediaDeviceSelectionResolver.resolveAudioInput(
                devices = listOf(builtIn),
                preferredDeviceId = usb.id,
            )
        val selectionWithUsb =
            MediaDeviceSelectionResolver.resolveAudioInput(
                devices = listOf(builtIn, usb),
                preferredDeviceId = usb.id,
            )

        assertEquals(builtIn.id, selectionWithoutUsb.selectedDeviceId)
        assertEquals(usb.id, selectionWithUsb.selectedDeviceId)
        assertNull(selectionWithUsb.routeControlMessage)
    }

    @Test
    fun `audio output resolver keeps the preferred output selected and controllable`() {
        val speaker = DefaultMediaDevices.systemOutput.copy(id = "speaker", label = "Built-in speaker".toUiText())
        val headset =
            DefaultMediaDevices.systemOutput.copy(
                id = "headset",
                label = "USB headset".toUiText(),
                category = MediaDeviceCategory.USB,
                isExternal = true,
            )

        val selection =
            MediaDeviceSelectionResolver.resolveAudioOutput(
                devices = listOf(speaker, headset),
                preferredDeviceId = headset.id,
            )

        assertEquals(headset.id, selection.selectedDeviceId)
        assertEquals("USB headset".toUiText(), selection.selectedDevice?.label)
        // Output routing is applied by the playback service now, so the picker is live rather
        // than deferring to Android's switcher.
        assertTrue(selection.isSelectionControllable)
        assertNull(selection.routeControlMessage)
    }

    @Test
    fun `audio input resolver falls back to system microphone when no devices are available`() {
        val selection = MediaDeviceSelectionResolver.resolveAudioInput(devices = emptyList(), preferredDeviceId = null)

        assertEquals(DefaultMediaDevices.systemMicrophone.id, selection.selectedDeviceId)
        assertEquals(DefaultMediaDevices.systemMicrophone.label, selection.selectedDevice?.label)
        assertEquals(false, selection.isSelectionControllable)
        assertEquals(
            Res.string.media_device_microphone_system_controlled.toUiText(),
            selection.routeControlMessage,
        )
    }

    @Test
    fun `audio output resolver falls back to system output when no devices are available`() {
        val selection = MediaDeviceSelectionResolver.resolveAudioOutput(devices = emptyList(), preferredDeviceId = null)

        assertEquals(DefaultMediaDevices.systemOutput.id, selection.selectedDeviceId)
        assertEquals(DefaultMediaDevices.systemOutput.label, selection.selectedDevice?.label)
        assertEquals(false, selection.isSelectionControllable)
        assertEquals(
            Res.string.media_device_output_system_controlled.toUiText(),
            selection.routeControlMessage,
        )
    }

    @Test
    fun `system controlled selection helper uses the shared route copy`() {
        val input = systemControlledSelection(MediaDeviceKind.AUDIO_INPUT)
        val output = systemControlledSelection(MediaDeviceKind.AUDIO_OUTPUT)
        val camera = systemControlledSelection(MediaDeviceKind.CAMERA)

        val overriddenInput =
            input.asSystemControlledSelection(
                routeControlMessage = "Open system settings to change the microphone.".toUiText(),
            )

        assertEquals(DefaultMediaDevices.systemMicrophone.label, input.selectedDevice?.label)
        assertEquals(
            Res.string.media_device_microphone_system_controlled.toUiText(),
            input.routeControlMessage,
        )

        assertEquals(DefaultMediaDevices.systemOutput.label, output.selectedDevice?.label)
        assertEquals(
            Res.string.media_device_output_system_controlled.toUiText(),
            output.routeControlMessage,
        )

        assertEquals(Res.string.media_device_back_camera.toUiText(), camera.selectedDevice?.label)
        assertEquals(
            Res.string.media_device_camera_system_controlled.toUiText(),
            camera.routeControlMessage,
        )

        assertEquals(false, overriddenInput.isSelectionControllable)
        assertEquals("Open system settings to change the microphone.".toUiText(), overriddenInput.routeControlMessage)
        assertEquals(input.devices, overriddenInput.devices)
    }
    // region output collapsing

    private fun output(
        id: String,
        label: String,
        groupId: String,
        qualityRank: Int,
        category: MediaDeviceCategory = MediaDeviceCategory.BLUETOOTH,
    ) = MediaDeviceUiState(
        id = id,
        label = label.toUiText(),
        kind = MediaDeviceKind.AUDIO_OUTPUT,
        category = category,
        isExternal = category != MediaDeviceCategory.BUILT_IN,
        groupId = groupId,
        qualityRank = qualityRank,
    )

    @Test
    fun `one pair of earbuds registered under three profiles collapses to a single row`() {
        // A real Pixel reports the same earbuds as A2DP, hands-free SCO and LE Audio, all
        // sharing one Bluetooth address and all labelled with the same product name.
        val selection =
            MediaDeviceSelectionResolver.resolveAudioOutput(
                devices =
                    listOf(
                        output("a2dp", "Alice's Earbuds v4", groupId = "AA:BB", qualityRank = 2),
                        output("sco", "Alice's Earbuds v4", groupId = "AA:BB", qualityRank = 1),
                        output("le", "Alice's Earbuds v4", groupId = "AA:BB", qualityRank = 3),
                    ),
                preferredDeviceId = null,
            )

        assertEquals(1, selection.devices.size)
        assertEquals("le", selection.devices.single().id)
    }

    @Test
    fun `two distinct devices stay two rows`() {
        val selection =
            MediaDeviceSelectionResolver.resolveAudioOutput(
                devices =
                    listOf(
                        output("earbuds-a2dp", "Earbuds", groupId = "AA:BB", qualityRank = 2),
                        output("earbuds-sco", "Earbuds", groupId = "AA:BB", qualityRank = 1),
                        output("speaker-a2dp", "Kitchen speaker", groupId = "CC:DD", qualityRank = 2),
                    ),
                preferredDeviceId = null,
            )

        assertEquals(listOf("Earbuds".toUiText(), "Kitchen speaker".toUiText()), selection.devices.map { it.label })
    }

    @Test
    fun `collapsing preserves the order devices were first seen`() {
        val selection =
            MediaDeviceSelectionResolver.resolveAudioOutput(
                devices =
                    listOf(
                        output("speaker", "Built-in speaker", groupId = "type-2", qualityRank = 0, category = MediaDeviceCategory.BUILT_IN),
                        output("earbuds-a2dp", "Earbuds", groupId = "AA:BB", qualityRank = 2),
                        output("earbuds-le", "Earbuds", groupId = "AA:BB", qualityRank = 3),
                    ),
                preferredDeviceId = null,
            )

        assertEquals(listOf("Built-in speaker".toUiText(), "Earbuds".toUiText()), selection.devices.map { it.label })
    }

    @Test
    fun `a preferred profile that lost to a better one still selects its device`() {
        // The stored preference names the A2DP entry, but LE Audio wins the group. The user
        // still expects their earbuds selected rather than a silent fall back to the speaker.
        val selection =
            MediaDeviceSelectionResolver.resolveAudioOutput(
                devices =
                    listOf(
                        output("speaker", "Built-in speaker", groupId = "type-2", qualityRank = 0, category = MediaDeviceCategory.BUILT_IN),
                        output("earbuds-a2dp", "Earbuds", groupId = "AA:BB", qualityRank = 2),
                        output("earbuds-le", "Earbuds", groupId = "AA:BB", qualityRank = 3),
                    ),
                preferredDeviceId = "earbuds-a2dp",
            )

        assertEquals("earbuds-le", selection.selectedDeviceId)
    }

    @Test
    fun `output selection is controllable once real devices exist`() {
        val selection =
            MediaDeviceSelectionResolver.resolveAudioOutput(
                devices =
                    listOf(
                        output("speaker", "Built-in speaker", groupId = "type-2", qualityRank = 0, category = MediaDeviceCategory.BUILT_IN),
                        output("earbuds-le", "Earbuds", groupId = "AA:BB", qualityRank = 3),
                    ),
                preferredDeviceId = null,
            )

        assertTrue(selection.isSelectionControllable)
    }

    // endregion
}
