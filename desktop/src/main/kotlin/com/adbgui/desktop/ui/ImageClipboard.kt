package com.adbgui.desktop.ui

import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Copies a PNG [bytes] image to the system clipboard as a [BufferedImage].
 * Decodes via ImageIO (independent of the Skia path used for display).
 * Returns true on success, false if decode or clipboard access fails.
 */
fun copyImageToClipboard(bytes: ByteArray): Boolean {
    val image = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return false
    return runCatching {
        val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(ImageSelection(image), ImageSelection(image))
        true
    }.getOrDefault(false)
}

private class ImageSelection(private val image: BufferedImage) : Transferable, ClipboardOwner {
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.imageFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.imageFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
        return image
    }
    override fun lostOwnership(clipboard: Clipboard, contents: Transferable) {}
}
