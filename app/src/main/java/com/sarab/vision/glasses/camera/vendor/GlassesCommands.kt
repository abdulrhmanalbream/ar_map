/* Copyright 2026 nudou350; portions Copyright (c) 2026 Aloim.
 * Licensed under Apache-2.0 with MIT attribution; see third_party/xreal-tools/.
 * Adapted for Sarab Vision: package relocation and bounded camera lifecycle.
 * Upstream revision bd9419200a04a047aee36c94c40d10f414f17abe.
 */
package com.sarab.vision.glasses.camera.vendor

/**
 * Sequência de comandos da ativação da câmera Eye, na ordem exata capturada do tráfego USB
 * emitido pela .so nativa (captura black-box; ver captures/run1.txt).
 *
 * Cada operação lógica é precedida por um par de preâmbulo (0x26, 0xd4) repetido 2x. O propósito
 * do preâmbulo NÃO é conhecido — pode ser handshake obrigatório ou hábito da lib deles.
 * Como este plano mira PARIDADE, o preâmbulo é reproduzido fielmente. Investigar se é removível
 * é trabalho da fase de otimização, não desta.
 */
object GlassesCommands {

    const val CMD_PREAMBLE_A = 0x26
    const val CMD_PREAMBLE_B = 0xd4
    const val CMD_GET_USB_CONFIG = 0xd2
    const val CMD_SET_USB_CONFIG = 0xd3
    const val CMD_GET_CAMERA_STATUS = 0xd5
    const val CMD_GET_PROPERTY = 0xd6

    const val PROP_APP_PREPARE_DONE = "ro.bsp.app_prepare_done"

    /** Resposta do WaitPilotReady contém este ASCII quando o pilot está pronto. */
    const val PILOT_READY_MARKER = "true"

    private fun preamble(): List<ByteArray> = listOf(
        GlassesFrame.build(CMD_PREAMBLE_A),
        GlassesFrame.build(CMD_PREAMBLE_B),
    )

    /** Uma operação = par de preâmbulo 2× + o comando dela. */
    fun waitPilotReadyMessages(): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_GET_PROPERTY, PROP_APP_PREPARE_DONE.toByteArray(Charsets.US_ASCII)))
    }

    fun getUsbConfigMessages(): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_GET_USB_CONFIG))
    }

    fun setUsbConfigMessages(payload: ByteArray): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_SET_USB_CONFIG, payload))
    }

    fun getCameraStatusMessages(): List<ByteArray> = buildList {
        addAll(preamble()); addAll(preamble())
        add(GlassesFrame.build(CMD_GET_CAMERA_STATUS))
    }

    fun enableCameraSequence(): List<ByteArray> =
        waitPilotReadyMessages() +
            getUsbConfigMessages() +
            setUsbConfigMessages(UsbConfigCodec.SET_UVC0_PAYLOAD) +
            getCameraStatusMessages()
}
